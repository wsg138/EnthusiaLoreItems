package net.enthusia.loreitems.application;

import java.util.Objects;
import java.util.UUID;
import net.enthusia.loreitems.domain.DefinitionKey;
import net.enthusia.loreitems.domain.LoreDefinition;

public record CreateDefinitionRequest(
        DefinitionKey key,
        String displayName,
        EncodedItemTemplate template,
        UUID actorId) {
    public CreateDefinitionRequest {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(actorId, "actorId");
        displayName = displayName.strip();
        if (displayName.isEmpty()
                || displayName.length() > LoreDefinition.MAX_DISPLAY_NAME_LENGTH) {
            throw new IllegalArgumentException("Definition display name must be 1-256 characters");
        }
    }
}
