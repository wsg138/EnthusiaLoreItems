package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class BoundedDebounceRegistryTest {
    private static final Instant START = Instant.parse("2026-08-07T12:00:00Z");

    @Test
    void duplicateIsCoalescedUntilExpiryAndThenAcceptedAgain() {
        MutableClock clock = new MutableClock(START);
        BoundedDebounceRegistry<String> registry =
                new BoundedDebounceRegistry<>(clock, Duration.ofSeconds(1L), 2);

        assertEquals(BoundedDebounceRegistry.OfferResult.ACCEPTED, registry.offer("player"));
        assertEquals(BoundedDebounceRegistry.OfferResult.DUPLICATE, registry.offer("player"));
        assertEquals(1, registry.size());

        clock.advance(Duration.ofSeconds(1L));
        assertEquals(BoundedDebounceRegistry.OfferResult.ACCEPTED, registry.offer("player"));
        assertEquals(1, registry.size());
    }

    @Test
    void fullRegistryEvictsOnlyDebounceStateAndNeverExceedsCapacity() {
        MutableClock clock = new MutableClock(START);
        BoundedDebounceRegistry<String> registry =
                new BoundedDebounceRegistry<>(clock, Duration.ofMinutes(1L), 2);

        assertEquals(BoundedDebounceRegistry.OfferResult.ACCEPTED, registry.offer("one"));
        clock.advance(Duration.ofMillis(1L));
        assertEquals(BoundedDebounceRegistry.OfferResult.ACCEPTED, registry.offer("two"));
        assertEquals(
                BoundedDebounceRegistry.OfferResult.ACCEPTED_AFTER_EVICTION,
                registry.offer("three"));
        assertEquals(2, registry.size());
        assertEquals(
                BoundedDebounceRegistry.OfferResult.ACCEPTED_AFTER_EVICTION,
                registry.offer("one"));
        assertEquals(2, registry.size());
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("Only UTC is supported");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
