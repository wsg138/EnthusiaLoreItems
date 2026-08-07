package net.enthusia.loreitems.domain;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

public enum CampaignRecipientState {
    UNRESOLVED,
    QUEUED_OFFLINE,
    QUEUED_INVENTORY_FULL,
    RESERVED_IN_FLIGHT,
    REVIEW_REQUIRED,
    DELIVERED,
    CANCELLED;

    /** Compatibility aliases for the pre-WP-03 foundation API. */
    public static final CampaignRecipientState PENDING_NAME = UNRESOLVED;
    public static final CampaignRecipientState PENDING_OFFLINE = QUEUED_OFFLINE;
    public static final CampaignRecipientState PENDING_SPACE = QUEUED_INVENTORY_FULL;
    public static final CampaignRecipientState RESERVED = RESERVED_IN_FLIGHT;

    public boolean terminal() {
        return this == DELIVERED || this == CANCELLED;
    }

    public boolean claimable() {
        return this == QUEUED_OFFLINE || this == QUEUED_INVENTORY_FULL;
    }

    public boolean canTransitionTo(CampaignRecipientState target) {
        Objects.requireNonNull(target, "target");
        return allowedTargets().contains(target);
    }

    public CampaignRecipientState transitionTo(CampaignRecipientState target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException(
                    "Invalid campaign recipient transition: " + this + " -> " + target);
        }
        return target;
    }

    private Set<CampaignRecipientState> allowedTargets() {
        return switch (this) {
            case UNRESOLVED -> EnumSet.of(QUEUED_OFFLINE, CANCELLED, REVIEW_REQUIRED);
            case QUEUED_OFFLINE, QUEUED_INVENTORY_FULL ->
                    EnumSet.of(RESERVED_IN_FLIGHT, CANCELLED, REVIEW_REQUIRED);
            case RESERVED_IN_FLIGHT -> EnumSet.of(
                    QUEUED_OFFLINE,
                    QUEUED_INVENTORY_FULL,
                    DELIVERED,
                    REVIEW_REQUIRED);
            case REVIEW_REQUIRED -> EnumSet.of(QUEUED_OFFLINE, CANCELLED);
            case DELIVERED, CANCELLED -> Set.of();
        };
    }
}
