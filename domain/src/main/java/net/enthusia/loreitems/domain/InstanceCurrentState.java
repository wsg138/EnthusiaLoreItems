package net.enthusia.loreitems.domain;

import java.util.Objects;

public record InstanceCurrentState(
        LoreInstanceId instanceId,
        State state,
        LocationDescriptor location,
        Long lastObservationId,
        long stateRevision,
        long updatedAtEpochMillis) {
    public InstanceCurrentState {
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(state, "state");
        if (stateRevision < 0L) {
            throw new IllegalArgumentException("stateRevision must not be negative");
        }
        if (updatedAtEpochMillis < 0L) {
            throw new IllegalArgumentException("updatedAtEpochMillis must not be negative");
        }
        if (state == State.MISSING_UNRESOLVED) {
            if (location != null || lastObservationId != null) {
                throw new IllegalArgumentException(
                        "Missing state must not claim a location or observation");
            }
        } else {
            Objects.requireNonNull(location, "location");
            if (lastObservationId == null || lastObservationId < 1L) {
                throw new IllegalArgumentException(
                        "Observed states require a positive last observation identifier");
            }
        }
        if (state == State.TERMINAL_VOID
                && location.type() != LocationDescriptor.Type.VOID_DESTROYED) {
            throw new IllegalArgumentException("Terminal void state requires a void location");
        }
        if (state != State.TERMINAL_VOID
                && location != null
                && location.type() == LocationDescriptor.Type.VOID_DESTROYED) {
            throw new IllegalArgumentException("Void location requires terminal void state");
        }
    }

    public enum State {
        CONFIRMED_NOW,
        LAST_CONFIRMED,
        CONFLICTING,
        TERMINAL_VOID,
        MISSING_UNRESOLVED
    }
}
