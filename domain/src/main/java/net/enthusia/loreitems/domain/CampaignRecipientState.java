package net.enthusia.loreitems.domain;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

public enum CampaignRecipientState {
    PENDING_NAME,
    PENDING_OFFLINE,
    PENDING_SPACE,
    RESERVED,
    DELIVERED,
    CANCELLED,
    REVIEW_REQUIRED;

    public boolean terminal() {
        return this == DELIVERED || this == CANCELLED || this == REVIEW_REQUIRED;
    }

    public boolean claimable() {
        return this == PENDING_OFFLINE || this == PENDING_SPACE;
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
            case PENDING_NAME -> EnumSet.of(PENDING_OFFLINE, CANCELLED, REVIEW_REQUIRED);
            case PENDING_OFFLINE, PENDING_SPACE ->
                    EnumSet.of(RESERVED, CANCELLED, REVIEW_REQUIRED);
            case RESERVED -> EnumSet.of(
                    PENDING_OFFLINE,
                    PENDING_SPACE,
                    DELIVERED,
                    REVIEW_REQUIRED);
            case DELIVERED, CANCELLED, REVIEW_REQUIRED -> Set.of();
        };
    }
}
