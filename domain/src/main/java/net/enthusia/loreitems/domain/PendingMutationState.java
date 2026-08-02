package net.enthusia.loreitems.domain;

import java.util.EnumSet;
import java.util.Set;

public enum PendingMutationState {
    PENDING,
    CLAIMED,
    APPLIED,
    VERIFIED,
    COMPLETED,
    REVIEW_REQUIRED;

    public boolean canTransitionTo(PendingMutationState target) {
        return allowedTargets().contains(target);
    }

    public PendingMutationState transitionTo(PendingMutationState target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException("Invalid mutation transition: " + this + " -> " + target);
        }
        return target;
    }

    private Set<PendingMutationState> allowedTargets() {
        return switch (this) {
            case PENDING -> EnumSet.of(CLAIMED, REVIEW_REQUIRED);
            case CLAIMED -> EnumSet.of(APPLIED, REVIEW_REQUIRED);
            case APPLIED -> EnumSet.of(VERIFIED, REVIEW_REQUIRED);
            case VERIFIED -> EnumSet.of(COMPLETED, REVIEW_REQUIRED);
            case COMPLETED, REVIEW_REQUIRED -> Set.of();
        };
    }
}
