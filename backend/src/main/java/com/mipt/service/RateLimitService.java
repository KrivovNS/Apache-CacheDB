package com.mipt.service;

import com.mipt.model.PermissionType;
import com.mipt.model.Session;
import com.mipt.ratelimit.RateLimitConfig;
import com.mipt.ratelimit.TokenBucket;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RateLimitService {
    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);

    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    private final RateLimitConfig defaultConfig;
    private final RateLimitConfig readerConfig;
    private final RateLimitConfig adminConfig;
    private final RateLimitConfig superAdminConfig;
    private final RateLimitConfig unauthenticatedConfig;

    private final ScheduledExecutorService cleanupScheduler;
    private final SessionService sessionService;
    private final boolean enabled;

    public RateLimitService(SessionService sessionService, boolean enabled) {
        this.sessionService = sessionService;
        this.enabled = enabled;

        this.unauthenticatedConfig = new RateLimitConfig(5);   // 5 req/s
        this.readerConfig = new RateLimitConfig(50);           // 50 req/s
        this.adminConfig = new RateLimitConfig(200);           // 200 req/s
        this.superAdminConfig = new RateLimitConfig(500);      // 500 req/s
        this.defaultConfig = new RateLimitConfig(100);

        this.cleanupScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rate-limit-cleanup");
            t.setDaemon(true);
            return t;
        });

        cleanupScheduler.scheduleAtFixedRate(
                this::cleanupExpiredBuckets, 5, 5, TimeUnit.MINUTES);
    }

    public RateLimitService(SessionService sessionService) {
        this(sessionService, true);
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
        buckets.entrySet().removeIf(entry ->
                entry.getValue().getAvailableTokens() == entry.getValue().getConfig().getCapacity()
        );
    }
}