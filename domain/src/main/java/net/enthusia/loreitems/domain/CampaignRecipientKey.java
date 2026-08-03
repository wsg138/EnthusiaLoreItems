package net.enthusia.loreitems.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record CampaignRecipientKey(String value) {
    public static final int MAX_VALUE_LENGTH = 320;
    public static final int MAX_ORIGINAL_VALUE_LENGTH = 256;
    private static final String NAME_PREFIX = "name:";
    private static final String UUID_PREFIX = "uuid:";

    public CampaignRecipientKey {
        Objects.requireNonNull(value, "value");
        value = value.strip();
        if (value.isEmpty() || value.length() > MAX_VALUE_LENGTH) {
            throw new IllegalArgumentException("Invalid campaign recipient key length");
        }
        if (value.startsWith(UUID_PREFIX)) {
            String suffix = value.substring(UUID_PREFIX.length());
            UUID parsed = UUID.fromString(suffix);
            if (!value.equals(UUID_PREFIX + parsed)) {
                throw new IllegalArgumentException("UUID recipient key must be canonical");
            }
        } else if (value.startsWith(NAME_PREFIX)) {
            String suffix = value.substring(NAME_PREFIX.length());
            if (suffix.isBlank() || containsControlCharacter(suffix)) {
                throw new IllegalArgumentException("Invalid unresolved-name recipient key");
            }
            if (!suffix.equals(suffix.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException(
                        "Unresolved-name recipient key must be case-normalized");
            }
        } else {
            throw new IllegalArgumentException(
                    "Campaign recipient key must use the name: or uuid: namespace");
        }
    }

    public static CampaignRecipientKey forPlayer(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return new CampaignRecipientKey(UUID_PREFIX + playerId);
    }

    public static CampaignRecipientKey forUnresolvedName(String originalValue) {
        String normalized = normalizeOriginalValue(originalValue);
        return new CampaignRecipientKey(NAME_PREFIX + normalized.toLowerCase(Locale.ROOT));
    }

    public boolean unresolvedNameKey() {
        return value.startsWith(NAME_PREFIX);
    }

    public boolean playerUuidKey() {
        return value.startsWith(UUID_PREFIX);
    }

    public UUID playerUuid() {
        if (!playerUuidKey()) {
            throw new IllegalStateException("Recipient key is not UUID-based");
        }
        return UUID.fromString(value.substring(UUID_PREFIX.length()));
    }

    public static String normalizeOriginalValue(String originalValue) {
        Objects.requireNonNull(originalValue, "originalValue");
        String normalized = originalValue.strip();
        if (normalized.isEmpty()
                || normalized.length() > MAX_ORIGINAL_VALUE_LENGTH
                || containsControlCharacter(normalized)) {
            throw new IllegalArgumentException("Invalid campaign recipient original value");
        }
        return normalized;
    }

    private static boolean containsControlCharacter(String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }
}
