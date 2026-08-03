package net.enthusia.loreitems.domain;

import java.util.Objects;

public record InstanceObservation(
        long observationId,
        LoreInstanceId instanceId,
        LoreDefinitionId definitionId,
        LocationDescriptor location,
        Confidence confidence,
        String source,
        long observedAtEpochMillis) {
    public static final int MAX_SOURCE_LENGTH = 120;

    public InstanceObservation {
        if (observationId < 0L) {
            throw new IllegalArgumentException("observationId must not be negative");
        }
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(definitionId, "definitionId");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(source, "source");
        source = source.strip();
        if (source.isEmpty() || source.length() > MAX_SOURCE_LENGTH) {
            throw new IllegalArgumentException("Invalid observation source");
        }
        if (observedAtEpochMillis < 0L) {
            throw new IllegalArgumentException("observedAtEpochMillis must not be negative");
        }
        if (confidence == Confidence.TERMINAL_VOID
                && location.type() != LocationDescriptor.Type.VOID_DESTROYED) {
            throw new IllegalArgumentException(
                    "Terminal void observations require a void location");
        }
        if (location.type() == LocationDescriptor.Type.VOID_DESTROYED
                && confidence != Confidence.TERMINAL_VOID) {
            throw new IllegalArgumentException(
                    "Void locations require terminal void confidence");
        }
    }

    public InstanceObservation withObservationId(long assignedObservationId) {
        if (observationId != 0L) {
            throw new IllegalStateException("Observation already has a persistent identifier");
        }
        if (assignedObservationId < 1L) {
            throw new IllegalArgumentException("assignedObservationId must be positive");
        }
        return new InstanceObservation(
                assignedObservationId,
                instanceId,
                definitionId,
                location,
                confidence,
                source,
                observedAtEpochMillis);
    }

    public enum Confidence {
        CONFIRMED_NOW,
        LAST_CONFIRMED,
        CONFLICTING,
        TERMINAL_VOID
    }
}
