package net.enthusia.loreitems.application;

import java.util.Objects;
import java.util.UUID;

public record PreparedTemplateUpdate(
        UUID mutationId,
        String claimToken,
        LoreItemIdentity observedIdentity,
        LoreItemIdentity targetIdentity,
        EncodedItemTemplate targetTemplate,
        long claimExpiresAtEpochMillis) {
    public static final int MAX_CLAIM_TOKEN_LENGTH = 200;

    public PreparedTemplateUpdate {
        Objects.requireNonNull(mutationId, "mutationId");
        Objects.requireNonNull(claimToken, "claimToken");
        Objects.requireNonNull(observedIdentity, "observedIdentity");
        Objects.requireNonNull(targetIdentity, "targetIdentity");
        Objects.requireNonNull(targetTemplate, "targetTemplate");
        claimToken = claimToken.strip();
        if (claimToken.isEmpty() || claimToken.length() > MAX_CLAIM_TOKEN_LENGTH) {
            throw new IllegalArgumentException("Invalid claim token");
        }
        if (!observedIdentity.definitionId().equals(targetIdentity.definitionId())
                || !observedIdentity.instanceId().equals(targetIdentity.instanceId())) {
            throw new IllegalArgumentException("Template update identities must refer to one instance");
        }
        if (targetIdentity.appliedRevision().value()
                < observedIdentity.appliedRevision().value()) {
            throw new IllegalArgumentException("Target revision must not precede the observed revision");
        }
        if (claimExpiresAtEpochMillis < 0L) {
            throw new IllegalArgumentException("Claim expiry must not be negative");
        }
    }
}
