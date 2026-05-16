package com.mipt.ratelimit;

import java.util.concurrent.atomic.AtomicLong;

public class TokenBucket {
    private final RateLimitConfig config;
    private final AtomicLong tokens;
    private final AtomicLong lastRefillTime;

    public TokenBucket(RateLimitConfig config) {
        this.config = config;
        this.tokens = new AtomicLong(config.getCapacity());
        this.lastRefillTime = new AtomicLong(System.currentTimeMillis());
    }

    public boolean tryAcquire() {
        refill();

        long current = tokens.get();
        while (current > 0) {
            if (tokens.compareAndSet(current, current - 1)) {
                return true;
            }
            current = tokens.get();
        }
        return false;
    }

    public boolean tryAcquire(int count) {
        if (count <= 0) return true;
        if (count > config.getCapacity()) return false;

        refill();

        long current = tokens.get();
        while (current >= count) {
            if (tokens.compareAndSet(current, current - count)) {
                return true;
            }
            current = tokens.get();
        }
        return false;
    }

    private void refill() {
        long now = System.currentTimeMillis();
        long lastRefill = lastRefillTime.get();
        long timeSinceLastRefill = now - lastRefill;

        if (timeSinceLastRefill >= config.getRefillPeriodMs()) {
            long periodsElapsed = timeSinceLastRefill / config.getRefillPeriodMs();
            long tokensToAdd = periodsElapsed * config.getRefillRate();

            if (tokensToAdd > 0) {
                long currentTokens = tokens.get();
                long newTokens = Math.min(currentTokens + tokensToAdd, config.getCapacity());
                tokens.compareAndSet(currentTokens, newTokens);
                lastRefillTime.compareAndSet(lastRefill, now);
            }
        }
    }

    public long getAvailableTokens() {
        refill();
        return tokens.get();
    }

    public void reset() {
        tokens.set(config.getCapacity());
        lastRefillTime.set(System.currentTimeMillis());
    }

    public RateLimitConfig getConfig() {
        return config;
    }
}