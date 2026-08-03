package net.enthusia.loreitems.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record DefinitionKey(String value) {
    private static final Pattern VALID_KEY = Pattern.compile("[a-z0-9](?:[a-z0-9_-]{0,63})");

    public DefinitionKey {
        Objects.requireNonNull(value, "value");
        value = value.strip().toLowerCase(Locale.ROOT);
        if (!VALID_KEY.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Definition key must be 1-64 lowercase letters, digits, underscores, or hyphens");
        }
    }
}
