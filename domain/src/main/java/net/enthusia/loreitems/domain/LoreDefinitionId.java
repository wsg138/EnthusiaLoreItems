package net.enthusia.loreitems.domain;

import java.util.Objects;
import java.util.UUID;

public record LoreDefinitionId(UUID value) {
    public LoreDefinitionId {
        Objects.requireNonNull(value, "value");
    }

    public static LoreDefinitionId random() {
        return new LoreDefinitionId(UUID.randomUUID());
    }
}
