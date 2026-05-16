package com.mipt.service;

import com.mipt.model.PermissionType;
import com.mipt.model.Session;
import com.mipt.ratelimit.RateLimitConfig;
import com.mipt.ratelimit.TokenBucket;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RateLimitService {
    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    private RateLimitConfig defaultConfig;
    private RateLimitConfig readerConfig;
    private RateLimitConfig adminConfig;
    private RateLimitConfig superAdminConfig;
    private RateLimitConfig unauthenticatedConfig;

    private final ScheduledExecutorService cleanupScheduler;
    private final SessionService sessionService;
    private final boolean enabled;

    public RateLimitService(SessionService sessionService, boolean enabled) {
        this.sessionService = sessionService;
        this.enabled = enabled;

        // Загружаем настройки из application.properties
        Properties props = loadProperties();
        loadRateLimitConfigs(props);
        int cleanupIntervalMinutes = getCleanupInterval(props);

        this.cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rate-limit-cleanup");
            t.setDaemon(true);
            return t;
        });

        cleanupScheduler.scheduleAtFixedRate(
                this::cleanupExpiredBuckets, cleanupIntervalMinutes, cleanupIntervalMinutes, TimeUnit.MINUTES);

        log.info("Rate limit cleanup scheduled every {} minutes", cleanupIntervalMinutes);
    }

    public RateLimitService(SessionService sessionService) {
        this(sessionService, true);
    }

    /**
     * Загружает properties из application.properties
     */
    private Properties loadProperties() {
        Properties props = new Properties();
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (input != null) {
                props.load(input);
            }
        } catch (IOException e) {
            log.warn("Could not load application.properties, using default rate limits");
        }
        return props;
    }

    /**
     * Загружает настройки rate limit из application.properties
     */
    private void loadRateLimitConfigs(Properties props) {
        int defaultLimit = getIntProperty(props, "ratelimit.default", 100);
        int unauthenticatedLimit = getIntProperty(props, "ratelimit.unauthenticated", 10);
        int readerLimit = getIntProperty(props, "ratelimit.reader", 50);
        int adminLimit = getIntProperty(props, "ratelimit.admin", 200);
        int superAdminLimit = getIntProperty(props, "ratelimit.superadmin", 500);

        this.defaultConfig = new RateLimitConfig(defaultLimit);
        this.unauthenticatedConfig = new RateLimitConfig(unauthenticatedLimit);
        this.readerConfig = new RateLimitConfig(readerLimit);
        this.adminConfig = new RateLimitConfig(adminLimit);
        this.superAdminConfig = new RateLimitConfig(superAdminLimit);

        log.info("Rate limit config loaded: default={}, unauthenticated={}, reader={}, admin={}, superadmin={}",
                defaultLimit, unauthenticatedLimit, readerLimit, adminLimit, superAdminLimit);
    }

    /**
     * Получает интервал очистки из properties
     */
    private int getCleanupInterval(Properties props) {
        int defaultInterval = 5; // 5 минут по умолчанию
        String value = props.getProperty("ratelimit.cleanup");
        if (value != null) {
            try {
                int interval = Integer.parseInt(value);
                if (interval > 0) {
                    return interval;
                }
                log.warn("ratelimit.cleanup must be positive, using default {}", defaultInterval);
            } catch (NumberFormatException e) {
                log.warn("Invalid value for ratelimit.cleanup: {}, using default {}", value, defaultInterval);
            }
        }
        return defaultInterval;
    }

    private int getIntProperty(Properties props, String key, int defaultValue) {
        String value = props.getProperty(key);
        if (value != null) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                log.warn("Invalid value for {}: {}, using default {}", key, value, defaultValue);
            }
        }
        return defaultValue;
    }

    public boolean allowRequest(String key) {
        if (!enabled) return true;
        TokenBucket bucket = getOrCreateBucket(key, unauthenticatedConfig);
        return bucket.tryAcquire();
    }

    public boolean allowRequest(String key, String sessionToken) {
        if (!enabled) return true;
        RateLimitConfig config = getConfigForSession(sessionToken);
        TokenBucket bucket = getOrCreateBucket(key, config);
        return bucket.tryAcquire();
    }

    public boolean allowBatchRequest(String key, int commandCount) {
        if (!enabled) return true;
        TokenBucket bucket = getOrCreateBucket(key, defaultConfig);
        return bucket.tryAcquire(commandCount);
    }

    public long getAvailableTokens(String key) {
        TokenBucket bucket = buckets.get(key);
        return bucket != null ? bucket.getAvailableTokens() : 0;
    }

    public void resetLimit(String key) {
        TokenBucket bucket = buckets.get(key);
        if (bucket != null) bucket.reset();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Map<String, Long> getStats() {
        Map<String, Long> stats = new ConcurrentHashMap<>();
        for (Map.Entry<String, TokenBucket> entry : buckets.entrySet()) {
            stats.put(entry.getKey(), entry.getValue().getAvailableTokens());
        }
        return stats;
    }

    public void shutdown() {
        cleanupScheduler.shutdown();
        try {
            if (!cleanupScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                cleanupScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private TokenBucket getOrCreateBucket(String key, RateLimitConfig config) {
        return buckets.computeIfAbsent(key, k -> new TokenBucket(config));
    }

    private RateLimitConfig getConfigForSession(String sessionToken) {
        if (sessionToken == null) return unauthenticatedConfig;
        try {
            Optional<Session> sessionOpt = sessionService.getValidSession(sessionToken);
            if (sessionOpt.isEmpty()) return unauthenticatedConfig;
            return switch (sessionOpt.get().getPermissionType()) {
                case READER -> readerConfig;
                case ADMIN -> adminConfig;
                case SUPERADMIN -> superAdminConfig;
            };
        } catch (Exception e) {
            return defaultConfig;
        }
    }

    private void cleanupExpiredBuckets() {
        int before = buckets.size();
        buckets.entrySet().removeIf(entry ->
                entry.getValue().getAvailableTokens() == entry.getValue().getConfig().getCapacity()
        );
        int removed = before - buckets.size();
        if (removed > 0) {
            log.debug("Cleaned up {} inactive rate limit buckets", removed);
        }
    }
}