package net.enthusia.loreitems.domain;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

public enum DistributionCampaignState {
    DRAFT,
    ACTIVE,
    PAUSED,
    COMPLETED,
    CANCELLED;

    public boolean terminal() {
        return this == COMPLETED || this == CANCELLED;
    }

    public boolean canTransitionTo(DistributionCampaignState target) {
        Objects.requireNonNull(target, "target");
        return allowedTargets().contains(target);
    }

    public DistributionCampaignState transitionTo(DistributionCampaignState target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException(
                    "Invalid distribution campaign transition: " + this + " -> " + target);
        }
        return target;
    }

    private Set<DistributionCampaignState> allowedTargets() {
        return switch (this) {
            case DRAFT -> EnumSet.of(ACTIVE, CANCELLED);
            case ACTIVE -> EnumSet.of(PAUSED, COMPLETED, CANCELLED);
            case PAUSED -> EnumSet.of(ACTIVE, COMPLETED, CANCELLED);
            case COMPLETED, CANCELLED -> Set.of();
        };
    }
}
