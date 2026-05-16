package com.mipt.ratelimit;

public class RateLimitConfig {
    private final int capacity;
    private final int refillRate;
    private final int refillPeriodMs;

    public RateLimitConfig(int capacity, int refillRate, int refillPeriodMs) {
        this.capacity = capacity;
        this.refillRate = refillRate;
        this.refillPeriodMs = refillPeriodMs;
    }

    public RateLimitConfig(int requestsPerSecond) {
        this(requestsPerSecond, requestsPerSecond, 1000);
    }

    public int getCapacity() {
        return capacity;
    }

    public int getRefillRate() {
        return refillRate;
    }

    public int getRefillPeriodMs() {
        return refillPeriodMs;
    }

    public static RateLimitConfig defaultConfig() {
        return new RateLimitConfig(100);
    }

    public static RateLimitConfig strict() {
        return new RateLimitConfig(10);
    }

    public static RateLimitConfig permissive() {
        return new RateLimitConfig(1000);
    }

    @Override
    public String toString() {
        return String.format("RateLimitConfig{capacity=%d, refillRate=%d, period=%dms}",
                capacity, refillRate, refillPeriodMs);
    }
}