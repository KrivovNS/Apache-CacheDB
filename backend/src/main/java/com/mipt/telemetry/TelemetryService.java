package com.mipt.telemetry;

import com.mipt.service.CacheStorageService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
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

public class TelemetryService implements AutoCloseable {

  private static final String METRIC_REQUESTS_TOTAL = "cachedb.requests.total";
  private static final String METRIC_MEMORY_USED = "cachedb.memory.used.bytes";
  private static final String METRIC_MEMORY_MAX = "cachedb.memory.max.bytes";

  private final CompositeMeterRegistry meterRegistry;
  private final PrometheusMeterRegistry prometheusRegistry;
  private final ConcurrentMap<String, Counter> requestCounters;

  private final OpenTelemetrySdk openTelemetrySdk;
  private final SdkTracerProvider tracerProvider;
  private final Tracer tracer;

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

    Gauge.builder(METRIC_MEMORY_USED, cacheStorageService, CacheStorageService::getUsedMemoryBytes)
        .description("Current memory used by cache database")
        .baseUnit("bytes")
        .register(meterRegistry);

    Gauge.builder(METRIC_MEMORY_MAX, cacheStorageService,
            CacheStorageService::getConfiguredMaxMemoryBytes)
        .description("Configured maximum memory for cache database")
        .baseUnit("bytes")
        .register(meterRegistry);

    String serviceName = properties.getProperty("telemetry.otel.service.name", "apache-cachedb");
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
    boolean success = statusCode >= 200 && statusCode < 300;
    recordRequest("http", requestType, success, statusCode);
  }

  public void recordTcpRequest(String requestType, boolean success) {
    recordRequest("tcp", requestType, success, null);
  }

  public String scrapePrometheus() {
    return prometheusRegistry.scrape();
  }

  private void recordRequest(String protocol, String requestType, boolean success, Integer statusCode) {
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

  private String normalizeRequestType(String requestType) {
    if (requestType == null || requestType.isBlank()) {
      return "unknown";
    }
    return requestType.trim().toLowerCase();
  }

  @Override
  public void close() {
    meterRegistry.close();
    tracerProvider.close();
    openTelemetrySdk.close();
  }
}
