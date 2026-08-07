package net.enthusia.loreitems.domain;

import java.util.EnumSet;
import java.util.Set;

public enum DestructiveOperationState {
    ACTIVE,
    PAUSED,
    COMPLETED,
    ABORTED;

    public boolean canTransitionTo(DestructiveOperationState target) {
        return allowedTargets().contains(target);
    }

    public DestructiveOperationState transitionTo(DestructiveOperationState target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException(
                    "Invalid destructive-operation transition: " + this + " -> " + target);
        }
        return target;
    }

    public boolean terminal() {
        return this == COMPLETED || this == ABORTED;
    }

    private Set<DestructiveOperationState> allowedTargets() {
        return switch (this) {
            case ACTIVE -> EnumSet.of(PAUSED, COMPLETED, ABORTED);
            case PAUSED -> EnumSet.of(ACTIVE, COMPLETED, ABORTED);
            case COMPLETED, ABORTED -> Set.of();
        };
    }
}