package net.enthusia.loreitems.domain;

import java.util.Objects;
import java.util.UUID;

public record LoreInstanceId(UUID value) {
    public LoreInstanceId {
        Objects.requireNonNull(value, "value");
    }

    public static LoreInstanceId random() {
        return new LoreInstanceId(UUID.randomUUID());
    }
}
