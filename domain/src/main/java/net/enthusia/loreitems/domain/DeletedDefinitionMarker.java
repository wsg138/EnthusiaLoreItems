package net.enthusia.loreitems.domain;

import java.util.Objects;

public record DeletedDefinitionMarker(
        LoreDefinitionId definitionId,
        DefinitionKey lookupKey,
        long deletedAtEpochMillis) {
    private static final long MIN_TIMESTAMP = 0L;

    public DeletedDefinitionMarker {
        Objects.requireNonNull(definitionId, "definitionId");
        Objects.requireNonNull(lookupKey, "lookupKey");
        if (deletedAtEpochMillis < MIN_TIMESTAMP) {
            throw new IllegalArgumentException("deletedAtEpochMillis must not be negative");
        }
    }
}
