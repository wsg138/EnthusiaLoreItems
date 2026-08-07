package net.enthusia.loreitems.paper;

import java.time.Clock;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Expiring size-capped debounce state that never retains Bukkit objects. */
final class BoundedDebounceRegistry<T> {
    private static final int MIN_CAPACITY = 1;

    private final Clock clock;
    private final long ttlMillis;
    private final int capacity;
    private final Map<T, Long> expirations = new LinkedHashMap<>();

    BoundedDebounceRegistry(Clock clock, Duration ttl, int capacity) {
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(ttl, "ttl");
        ttlMillis = ttl.toMillis();
        if (ttlMillis < 1L) {
            throw new IllegalArgumentException("Debounce TTL must be positive");
        }
        if (capacity < MIN_CAPACITY) {
            throw new IllegalArgumentException("Debounce capacity must be positive");
        }
        this.capacity = capacity;
    }

    synchronized OfferResult offer(T key) {
        Objects.requireNonNull(key, "key");
        long now = clock.millis();
        removeExpired(now);
        Long currentExpiry = expirations.get(key);
        if (currentExpiry != null && currentExpiry > now) {
            return OfferResult.DUPLICATE;
        }
        boolean evicted = false;
        if (expirations.size() >= capacity) {
            Iterator<T> keys = expirations.keySet().iterator();
            if (keys.hasNext()) {
                keys.next();
                keys.remove();
                evicted = true;
            }
        }
        expirations.put(key, Math.addExact(now, ttlMillis));
        return evicted ? OfferResult.ACCEPTED_AFTER_EVICTION : OfferResult.ACCEPTED;
    }

    synchronized int size() {
        removeExpired(clock.millis());
        return expirations.size();
    }

    synchronized void clear() {
        expirations.clear();
    }

    private void removeExpired(long now) {
        Iterator<Map.Entry<T, Long>> entries = expirations.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<T, Long> entry = entries.next();
            if (entry.getValue() > now) {
                break;
            }
            entries.remove();
        }
    }

    enum OfferResult {
        ACCEPTED,
        ACCEPTED_AFTER_EVICTION,
        DUPLICATE
    }
}
