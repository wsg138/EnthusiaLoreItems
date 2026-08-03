package net.enthusia.loreitems.domain;

import java.util.Objects;

public record DeletedDefinitionMarker(
        LoreDefinitionId definitionId,
        DefinitionKey lookupKey,
        long deletedAtEpochMillis) {
    public DeletedDefinitionMarker {
        Objects.requireNonNull(definitionId, "definitionId");
        Objects.requireNonNull(lookupKey, "lookupKey");
        if (deletedAtEpochMillis < 0L) {
            throw new IllegalArgumentException("deletedAtEpochMillis must not be negative");
        }
    }
}
