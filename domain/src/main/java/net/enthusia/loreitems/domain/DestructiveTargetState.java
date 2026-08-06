package net.enthusia.loreitems.domain;

import java.util.EnumSet;
import java.util.Set;

public enum DestructiveTargetState {
    PENDING,
    CLAIMED,
    APPLIED,
    VERIFIED,
    COMPLETED,
    REVIEW_REQUIRED,
    ABORTED;

    public boolean canTransitionTo(DestructiveTargetState target) {
        return allowedTargets().contains(target);
    }

    public DestructiveTargetState transitionTo(DestructiveTargetState target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException(
                    "Invalid destructive-target transition: " + this + " -> " + target);
        }
        return target;
    }

    public boolean terminal() {
        return this == COMPLETED || this == ABORTED;
    }

    private Set<DestructiveTargetState> allowedTargets() {
        return switch (this) {
            case PENDING -> EnumSet.of(CLAIMED, REVIEW_REQUIRED, ABORTED);
            case CLAIMED -> EnumSet.of(APPLIED, REVIEW_REQUIRED, PENDING);
            case APPLIED -> EnumSet.of(VERIFIED, REVIEW_REQUIRED);
            case VERIFIED -> EnumSet.of(COMPLETED, REVIEW_REQUIRED);
            case REVIEW_REQUIRED -> EnumSet.of(PENDING, VERIFIED, ABORTED);
            case COMPLETED, ABORTED -> Set.of();
        };
    }
}