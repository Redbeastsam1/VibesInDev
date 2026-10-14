package dev.vibesindev.vibesmusic.util;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class RateLimiter {

    private final int limitPerMinute;
    private final Map<UUID, PlayerRateInfo> rates = new ConcurrentHashMap<>();

    public RateLimiter(int limitPerMinute) {
        this.limitPerMinute = limitPerMinute;
    }

    public boolean tryAcquire(UUID player) {
        if (limitPerMinute <= 0) return true;

        PlayerRateInfo info = rates.computeIfAbsent(player, k -> new PlayerRateInfo());
        long now = System.currentTimeMillis();

        synchronized (info) {
            if (now - info.windowStart > 60_000) {
                info.windowStart = now;
                info.count.set(0);
            }

            if (info.count.incrementAndGet() > limitPerMinute) {
                return false;
            }

            return true;
        }
    }

    public void cleanup() {
        long now = System.currentTimeMillis();
        rates.entrySet().removeIf(e -> now - e.getValue().windowStart > 120_000);
    }

    private static class PlayerRateInfo {
        long windowStart = System.currentTimeMillis();
        final AtomicInteger count = new AtomicInteger(0);
    }
}
