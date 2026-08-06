package net.enthusia.loreitems.paper;

import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase;
import net.enthusia.loreitems.domain.DestructiveOperationType;

/** Bounded, operation-specific destructive confirmation sessions for the latest actor preview. */
final class DestructiveConfirmationRegistry {
    private static final int MIN_CAPACITY = 1;

    private final Clock clock;
    private final long ttlMillis;
    private final int capacity;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    DestructiveConfirmationRegistry(Clock clock, Duration ttl, int capacity) {
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(ttl, "ttl");
        ttlMillis = ttl.toMillis();
        if (ttlMillis < MIN_CAPACITY) {
            throw new IllegalArgumentException("Confirmation TTL must be positive");
        }
        if (capacity < MIN_CAPACITY) {
            throw new IllegalArgumentException("Confirmation capacity must be positive");
        }
        this.capacity = capacity;
    }

    Session remember(String actorId, DestructiveAdministrationUseCase.Preview preview) {
        synchronized (sessions) {
            String normalizedActor = requireActor(actorId);
            Objects.requireNonNull(preview, "preview");
            long now = clock.millis();
            removeExpired(now);
            if (!sessions.containsKey(normalizedActor) && sessions.size() >= capacity) {
                sessions.values().stream()
                        .min(Comparator.comparingLong(Session::expiresAtEpochMillis))
                        .ifPresent(oldest -> sessions.remove(oldest.actorId()));
            }
            Session session = new Session(
                    normalizedActor,
                    preview.operationType(),
                    preview,
                    UUID.randomUUID().toString(),
                    Math.addExact(now, ttlMillis));
            sessions.put(normalizedActor, session);
            return session;
        }
    }

    Optional<Session> consume(
            String actorId, DestructiveOperationType operationType, String confirmationToken) {
        synchronized (sessions) {
            String normalizedActor = requireActor(actorId);
            Objects.requireNonNull(operationType, "operationType");
            String normalizedToken = Objects.requireNonNull(
                            confirmationToken, "confirmationToken")
                    .strip();
            long now = clock.millis();
            removeExpired(now);
            Session session = sessions.get(normalizedActor);
            if (session == null
                    || session.operationType() != operationType
                    || !session.preview().confirmationToken().equals(normalizedToken)) {
                return Optional.empty();
            }
            sessions.remove(normalizedActor);
            return Optional.of(session);
        }
    }

    void clear() {
        synchronized (sessions) {
            sessions.clear();
        }
    }

    int size() {
        synchronized (sessions) {
            removeExpired(clock.millis());
            return sessions.size();
        }
    }

    private void removeExpired(long now) {
        sessions.values().removeIf(session -> session.expiresAtEpochMillis() <= now);
    }

    private static String requireActor(String actorId) {
        String normalized = Objects.requireNonNull(actorId, "actorId").strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Actor must not be blank");
        }
        return normalized;
    }

    record Session(
            String actorId,
            DestructiveOperationType operationType,
            DestructiveAdministrationUseCase.Preview preview,
            String idempotencyKey,
            long expiresAtEpochMillis) {
        Session {
            actorId = requireActor(actorId);
            Objects.requireNonNull(operationType, "operationType");
            Objects.requireNonNull(preview, "preview");
            Objects.requireNonNull(idempotencyKey, "idempotencyKey");
            if (preview.operationType() != operationType) {
                throw new IllegalArgumentException("Confirmation operation type must match preview");
            }
        }
    }
}
