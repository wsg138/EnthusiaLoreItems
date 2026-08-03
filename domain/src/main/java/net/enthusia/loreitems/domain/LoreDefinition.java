package net.enthusia.loreitems.domain;

import java.util.Objects;

public record LoreDefinition(
        LoreDefinitionId id,
        DefinitionKey key,
        String displayName,
        TemplateRevision currentRevision,
        long createdAtEpochMillis,
        Long deletedAtEpochMillis) {
    private static final long MIN_TIMESTAMP = 0L;

    public static final int MAX_DISPLAY_NAME_LENGTH = 256;

    public LoreDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(currentRevision, "currentRevision");
        displayName = displayName.strip();
        if (displayName.isEmpty() || displayName.length() > MAX_DISPLAY_NAME_LENGTH) {
            throw new IllegalArgumentException("Definition display name must be 1-256 characters");
        }
        if (createdAtEpochMillis < MIN_TIMESTAMP) {
            throw new IllegalArgumentException("createdAtEpochMillis must not be negative");
        }
        if (deletedAtEpochMillis != null && deletedAtEpochMillis < createdAtEpochMillis) {
            throw new IllegalArgumentException("deletedAtEpochMillis must not precede creation");
        }
    }

    public boolean active() {
        return deletedAtEpochMillis == null;
    }
}
