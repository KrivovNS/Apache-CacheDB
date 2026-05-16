package com.mipt.telemetry;

import com.mipt.service.CacheStorageService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics;
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics;
import io.micrometer.core.instrument.binder.logging.LogbackMetrics;
import io.micrometer.core.instrument.binder.system.FileDescriptorMetrics;
import io.micrometer.core.instrument.binder.system.ProcessorMetrics;
import io.micrometer.core.instrument.binder.system.UptimeMetrics;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

public class TelemetryService implements AutoCloseable {

  private static final String METRIC_REQUESTS_TOTAL = "cachedb.requests.total";
  private static final String METRIC_REQUEST_LATENCY = "cachedb.requests.latency";
  private static final String METRIC_MEMORY_USED = "cachedb.memory.used.bytes";
  private static final String METRIC_MEMORY_MAX = "cachedb.memory.max.bytes";

  private final CompositeMeterRegistry meterRegistry;
  private final PrometheusMeterRegistry prometheusRegistry;
  private final ConcurrentMap<String, Counter> requestCounters;
  private final ConcurrentMap<String, Timer> requestLatencyTimers;
  private final ConcurrentMap<String, Timer> httpRequestTimers;

  private final OpenTelemetrySdk openTelemetrySdk;
  private final SdkTracerProvider tracerProvider;
  private final Tracer tracer;
  private final JvmGcMetrics jvmGcMetrics;
  private final LogbackMetrics logbackMetrics;

  public static TelemetryService disabled(CacheStorageService cacheStorageService) {
    Properties properties = new Properties();
    properties.setProperty("telemetry.otel.enabled", "false");
    return new TelemetryService(properties, cacheStorageService);
  }

  public TelemetryService(Properties properties, CacheStorageService cacheStorageService) {
    Objects.requireNonNull(properties, "properties");
    Objects.requireNonNull(cacheStorageService, "cacheStorageService");

    this.prometheusRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    this.meterRegistry = new CompositeMeterRegistry();
    this.meterRegistry.add(prometheusRegistry);
    this.requestCounters = new ConcurrentHashMap<>();
    this.requestLatencyTimers = new ConcurrentHashMap<>();
    this.httpRequestTimers = new ConcurrentHashMap<>();

    String serviceName = properties.getProperty("telemetry.otel.service.name", "apache-cachedb");
    this.meterRegistry.config().commonTags("application", serviceName);

    new ClassLoaderMetrics().bindTo(meterRegistry);
    new JvmMemoryMetrics().bindTo(meterRegistry);
    this.jvmGcMetrics = new JvmGcMetrics();
    this.jvmGcMetrics.bindTo(meterRegistry);
    new ProcessorMetrics().bindTo(meterRegistry);
    new JvmThreadMetrics().bindTo(meterRegistry);
    new UptimeMetrics().bindTo(meterRegistry);
    new FileDescriptorMetrics().bindTo(meterRegistry);
    this.logbackMetrics = new LogbackMetrics();
    this.logbackMetrics.bindTo(meterRegistry);

    Gauge.builder(METRIC_MEMORY_USED, cacheStorageService, CacheStorageService::getUsedMemoryBytes)
        .description("Current memory used by cache database")
        .baseUnit("bytes")
        .register(meterRegistry);

    Gauge.builder(METRIC_MEMORY_MAX, cacheStorageService,
            CacheStorageService::getConfiguredMaxMemoryBytes)
        .description("Configured maximum memory for cache database")
        .baseUnit("bytes")
        .register(meterRegistry);

    boolean otelEnabled = Boolean.parseBoolean(properties.getProperty("telemetry.otel.enabled", "true"));

    Resource resource = Resource.getDefault()
        .merge(Resource.builder().put("service.name", serviceName).build());

    SdkTracerProviderBuilder tracerProviderBuilder = SdkTracerProvider.builder()
        .setResource(resource);

    if (otelEnabled) {
      String endpoint = properties.getProperty("telemetry.otel.endpoint", "http://localhost:4317");
      OtlpGrpcSpanExporter exporter = OtlpGrpcSpanExporter.builder()
          .setEndpoint(endpoint)
          .build();
      tracerProviderBuilder.addSpanProcessor(BatchSpanProcessor.builder(exporter).build());
    }

    this.tracerProvider = tracerProviderBuilder.build();
    this.openTelemetrySdk = OpenTelemetrySdk.builder()
        .setTracerProvider(tracerProvider)
        .build();
    this.tracer = openTelemetrySdk.getTracer("com.mipt.telemetry");
  }

  public void recordHttpRequest(String requestType, int statusCode) {
    recordHttpRequest(requestType, statusCode, -1L);
  }

  public void recordHttpRequest(String requestType, int statusCode, long latencyNanos) {
    boolean success = statusCode >= 200 && statusCode < 300;
    recordRequest("http", requestType, success, statusCode, latencyNanos);
    recordHttpServerRequest(requestType, statusCode, latencyNanos);
  }

  public void recordTcpRequest(String requestType, boolean success) {
    recordTcpRequest(requestType, success, -1L);
  }

  public void recordTcpRequest(String requestType, boolean success, long latencyNanos) {
    recordRequest("tcp", requestType, success, null, latencyNanos);
  }

  public String scrapePrometheus() {
    return prometheusRegistry.scrape();
  }

  private void recordRequest(String protocol, String requestType, boolean success,
      Integer statusCode, long latencyNanos) {
    String normalizedType = normalizeRequestType(requestType);
    String resultTag = success ? "success" : "failure";
    String cacheKey = protocol + "|" + normalizedType + "|" + resultTag;

    Counter counter = requestCounters.computeIfAbsent(cacheKey, ignored ->
        Counter.builder(METRIC_REQUESTS_TOTAL)
            .description("Total number of requests by protocol, type and result")
            .tag("protocol", protocol)
            .tag("request_type", normalizedType)
            .tag("result", resultTag)
            .register(meterRegistry)
    );
    counter.increment();
    recordRequestLatency(protocol, normalizedType, resultTag, statusCode, latencyNanos);

    Span span = tracer.spanBuilder(protocol + ":" + normalizedType).startSpan();
    try (Scope ignored = span.makeCurrent()) {
      span.setAttribute("cachedb.protocol", protocol);
      span.setAttribute("cachedb.request.type", normalizedType);
      span.setAttribute("cachedb.request.success", success);
      if (statusCode != null) {
        span.setAttribute("http.status_code", statusCode);
      }
    } finally {
      span.end();
    }
  }

  private void recordRequestLatency(String protocol, String requestType, String resultTag,
      Integer statusCode, long latencyNanos) {
    if (latencyNanos < 0) {
      return;
    }

    String statusTag = statusCode == null ? "none" : String.valueOf(statusCode);
    String cacheKey = protocol + "|" + requestType + "|" + resultTag + "|" + statusTag;

    Timer timer = requestLatencyTimers.computeIfAbsent(cacheKey, ignored ->
        Timer.builder(METRIC_REQUEST_LATENCY)
            .description("Request latency by protocol, request type and result")
            .tag("protocol", protocol)
            .tag("request_type", requestType)
            .tag("result", resultTag)
            .tag("status_code", statusTag)
            .publishPercentileHistogram()
            .register(meterRegistry)
    );
    timer.record(latencyNanos, TimeUnit.NANOSECONDS);
  }

  private void recordHttpServerRequest(String requestType, int statusCode, long latencyNanos) {
    if (latencyNanos < 0) {
      return;
    }

    String normalizedType = normalizeRequestType(requestType);
    String method = "UNKNOWN";
    String uri = "unknown";

    String[] parts = normalizedType.split("\\.");
    if (parts.length >= 3 && "http".equals(parts[0])) {
      uri = parts[1];
      method = parts[2].toUpperCase();
    }

    String status = String.valueOf(statusCode);
    String outcome = resolveOutcome(statusCode);
    String methodTag = method;
    String uriTag = uri;
    String cacheKey = methodTag + "|" + uriTag + "|" + status + "|" + outcome;

    Timer timer = httpRequestTimers.computeIfAbsent(cacheKey, ignored ->
        Timer.builder("http.server.requests")
            .description("HTTP server request duration")
            .tag("method", methodTag)
            .tag("uri", uriTag)
            .tag("status", status)
            .tag("outcome", outcome)
            .register(meterRegistry)
    );

    timer.record(latencyNanos, TimeUnit.NANOSECONDS);
  }

  private String resolveOutcome(int statusCode) {
    if (statusCode >= 200 && statusCode < 300) {
      return "SUCCESS";
    }
    if (statusCode >= 300 && statusCode < 400) {
      return "REDIRECTION";
    }
    if (statusCode >= 400 && statusCode < 500) {
      return "CLIENT_ERROR";
    }
    if (statusCode >= 500 && statusCode < 600) {
      return "SERVER_ERROR";
    }
    return "UNKNOWN";
  }

  private String normalizeRequestType(String requestType) {
    if (requestType == null || requestType.isBlank()) {
      return "unknown";
    }
    return requestType.trim().toLowerCase();
  }

  @Override
  public void close() {
    try {
      jvmGcMetrics.close();
    } catch (Exception ignored) {
    }
    try {
      logbackMetrics.close();
    } catch (Exception ignored) {
    }
    meterRegistry.close();
    tracerProvider.close();
    openTelemetrySdk.close();
  }
}
