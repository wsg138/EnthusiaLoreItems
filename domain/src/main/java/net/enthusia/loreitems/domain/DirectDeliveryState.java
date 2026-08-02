package net.enthusia.loreitems.domain;

import java.util.EnumSet;
import java.util.Set;

public enum DirectDeliveryState {
    PENDING,
    RESERVED,
    APPLIED,
    VERIFIED,
    COMPLETED,
    REVIEW_REQUIRED;

    public boolean canTransitionTo(DirectDeliveryState target) {
        return allowedTargets().contains(target);
    }

    public DirectDeliveryState transitionTo(DirectDeliveryState target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException("Invalid delivery transition: " + this + " -> " + target);
        }
        return target;
    }

    private Set<DirectDeliveryState> allowedTargets() {
        if (this == COMPLETED || this == REVIEW_REQUIRED) {
            return Set.of();
        }
        DirectDeliveryState next = switch (this) {
            case PENDING -> RESERVED;
            case RESERVED -> APPLIED;
            case APPLIED -> VERIFIED;
            case VERIFIED -> COMPLETED;
            case COMPLETED, REVIEW_REQUIRED -> throw new IllegalStateException("Terminal state");
        };
        return EnumSet.of(next, REVIEW_REQUIRED);
    }
}
