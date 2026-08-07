package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase;
import net.enthusia.loreitems.domain.DefinitionKey;
import net.enthusia.loreitems.domain.DestructiveOperationType;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.junit.jupiter.api.Test;

class DestructiveConfirmationRegistryTest {
    private static final Instant NOW = Instant.parse("2026-08-06T08:00:00Z");
    private static final String ACTOR = "actor";
    private static final String ACTOR_ONE = "actor-one";
    private static final String REMOVE_TOKEN = "remove-token";
    private static final String TOKEN = "token";

    @Test
    void consumesOnlyTheMatchingActorOperationAndToken() {
        DestructiveConfirmationRegistry registry = registryAt(NOW, 4);
        DestructiveAdministrationUseCase.Preview preview = preview(
                DestructiveOperationType.EXACT_INSTANCE_REMOVAL, REMOVE_TOKEN);
        registry.remember(ACTOR_ONE, preview);

        assertTrue(registry.consume(
                        ACTOR_ONE,
                        DestructiveOperationType.PURGE_DEFINITION,
                        REMOVE_TOKEN)
                .isEmpty());
        assertTrue(registry.consume(
                        "actor-two",
                        DestructiveOperationType.EXACT_INSTANCE_REMOVAL,
                        REMOVE_TOKEN)
                .isEmpty());
        assertTrue(registry.consume(
                        ACTOR_ONE,
                        DestructiveOperationType.EXACT_INSTANCE_REMOVAL,
                        "wrong-token")
                .isEmpty());

        Optional<DestructiveConfirmationRegistry.Session> confirmed = registry.consume(
                ACTOR_ONE,
                DestructiveOperationType.EXACT_INSTANCE_REMOVAL,
                REMOVE_TOKEN);
        assertTrue(confirmed.isPresent());
        assertEquals(preview, confirmed.orElseThrow().preview());
        assertEquals(0, registry.size());
    }

    @Test
    void replacingAnActorPreviewInvalidatesTheOlderSnapshot() {
        DestructiveConfirmationRegistry registry = registryAt(NOW, 4);
        registry.remember(
                ACTOR,
                preview(DestructiveOperationType.PURGE_DEFINITION, "old-token"));
        registry.remember(
                ACTOR,
                preview(DestructiveOperationType.DELETE_DEFINITION, "new-token"));

        assertTrue(registry.consume(
                        ACTOR,
                        DestructiveOperationType.PURGE_DEFINITION,
                        "old-token")
                .isEmpty());
        assertTrue(registry.consume(
                        ACTOR,
                        DestructiveOperationType.DELETE_DEFINITION,
                        "new-token")
                .isPresent());
    }

    @Test
    void evictsTheOldestSessionWhenCapacityIsReached() {
        MutableClock clock = new MutableClock(NOW);
        DestructiveConfirmationRegistry registry = new DestructiveConfirmationRegistry(
                clock, Duration.ofMinutes(5L), 1);
        registry.remember(
                "first", preview(DestructiveOperationType.PURGE_DEFINITION, "first-token"));
        clock.advance(Duration.ofMillis(1L));
        registry.remember(
                "second", preview(DestructiveOperationType.PURGE_DEFINITION, "second-token"));

        assertEquals(1, registry.size());
        assertTrue(registry.consume(
                        "first",
                        DestructiveOperationType.PURGE_DEFINITION,
                        "first-token")
                .isEmpty());
        assertTrue(registry.consume(
                        "second",
                        DestructiveOperationType.PURGE_DEFINITION,
                        "second-token")
                .isPresent());
    }

    @Test
    void expiresSessionsAfterTheFixedConfirmationWindow() {
        MutableClock clock = new MutableClock(NOW);
        DestructiveConfirmationRegistry registry = new DestructiveConfirmationRegistry(
                clock, Duration.ofMinutes(5L), 4);
        registry.remember(
                ACTOR, preview(DestructiveOperationType.PURGE_DEFINITION, TOKEN));

        clock.advance(Duration.ofMinutes(5L));

        assertEquals(0, registry.size());
        assertTrue(registry.consume(ACTOR, DestructiveOperationType.PURGE_DEFINITION, TOKEN)
                .isEmpty());
    }

    private static DestructiveConfirmationRegistry registryAt(Instant instant, int capacity) {
        return new DestructiveConfirmationRegistry(
                Clock.fixed(instant, ZoneOffset.UTC), Duration.ofMinutes(5L), capacity);
    }

    private static DestructiveAdministrationUseCase.Preview preview(
            DestructiveOperationType operationType, String token) {
        LoreInstanceId instanceId = operationType.exactInstanceRequired()
                ? new LoreInstanceId(UUID.randomUUID())
                : null;
        return new DestructiveAdministrationUseCase.Preview(
                operationType,
                LoreDefinitionId.random(),
                new DefinitionKey("test-definition"),
                "Test Definition",
                new TemplateRevision(1L),
                instanceId,
                1L,
                0L,
                0L,
                0L,
                token);
    }

    private static final class MutableClock extends Clock {
        private Instant currentInstant;

        private MutableClock(Instant instant) {
            currentInstant = instant;
        }

        private void advance(Duration duration) {
            currentInstant = currentInstant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("Only UTC is supported by this test clock");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return currentInstant;
        }
    }
}
