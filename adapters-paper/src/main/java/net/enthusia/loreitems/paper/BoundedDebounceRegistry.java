package net.enthusia.loreitems.paper;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Expiring size-capped debounce state that never retains Bukkit objects. */
final class BoundedDebounceRegistry<T> {
    private static final int MIN_CAPACITY = 1;
    private static final long MIN_TTL_MILLIS = 1L;

    private final Clock clock;
    private final long ttlMillis;
    private final int capacity;
    private final Map<T, Long> expirations = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    BoundedDebounceRegistry(Clock clock, Duration ttl, int capacity) {
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(ttl, "ttl");
        ttlMillis = ttl.toMillis();
        if (ttlMillis < MIN_TTL_MILLIS) {
            throw new IllegalArgumentException("Debounce TTL must be positive");
        }
        if (capacity < MIN_CAPACITY) {
            throw new IllegalArgumentException("Debounce capacity must be positive");
        }
        this.capacity = capacity;
    }

    OfferResult offer(T key) {
        Objects.requireNonNull(key, "key");
        lock.lock();
        try {
            long now = clock.millis();
            removeExpired(now);
            Long currentExpiry = expirations.get(key);
            if (currentExpiry != null && currentExpiry > now) {
                return OfferResult.DUPLICATE;
            }
            boolean evicted = expirations.size() >= capacity && evictOldest();
            expirations.put(key, Math.addExact(now, ttlMillis));
            return evicted ? OfferResult.ACCEPTED_AFTER_EVICTION : OfferResult.ACCEPTED;
        } finally {
            lock.unlock();
        }
    }

    int size() {
        lock.lock();
        try {
            removeExpired(clock.millis());
            return expirations.size();
        } finally {
            lock.unlock();
        }
    }

    void clear() {
        lock.lock();
        try {
            expirations.clear();
        } finally {
            lock.unlock();
        }
    }

    private boolean evictOldest() {
        T oldestKey = null;
        long oldestExpiry = Long.MAX_VALUE;
        for (Map.Entry<T, Long> entry : expirations.entrySet()) {
            if (entry.getValue() < oldestExpiry) {
                oldestExpiry = entry.getValue();
                oldestKey = entry.getKey();
            }
        }
        return oldestKey != null && expirations.remove(oldestKey) != null;
    }

    private void removeExpired(long now) {
        expirations.entrySet().removeIf(entry -> entry.getValue() <= now);
    }

    enum OfferResult {
        ACCEPTED,
        ACCEPTED_AFTER_EVICTION,
        DUPLICATE
    }
}
