package net.enthusia.loreitems.domain;

import java.util.EnumSet;
import java.util.Set;

public enum DirectDeliveryState {
    PENDING,
    RESERVED,
    APPLIED,
    VERIFIED,
    COMPLETED,
    CANCELLED,
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
        return switch (this) {
            case PENDING -> EnumSet.of(RESERVED, CANCELLED, REVIEW_REQUIRED);
            case RESERVED -> EnumSet.of(APPLIED, REVIEW_REQUIRED);
            case APPLIED -> EnumSet.of(VERIFIED, REVIEW_REQUIRED);
            case VERIFIED -> EnumSet.of(COMPLETED, REVIEW_REQUIRED);
            case COMPLETED, CANCELLED, REVIEW_REQUIRED -> Set.of();
        };
    }
}
