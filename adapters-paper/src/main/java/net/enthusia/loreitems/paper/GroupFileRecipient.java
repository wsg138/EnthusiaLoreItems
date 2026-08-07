package net.enthusia.loreitems.paper;

import java.util.Objects;
import java.util.UUID;
import net.enthusia.loreitems.domain.CampaignRecipientKey;

public record GroupFileRecipient(
        String originalValue,
        CampaignRecipientKey recipientKey,
        UUID explicitPlayerId) {
    private static final int UUID_TEXT_LENGTH = 36;
    private static final int UUID_FIRST_SEPARATOR = 8;
    private static final int UUID_SECOND_SEPARATOR = 13;
    private static final int UUID_THIRD_SEPARATOR = 18;
    private static final int UUID_FOURTH_SEPARATOR = 23;
    private static final char UUID_SEPARATOR = '-';
    private static final int FORWARD_SLASH = '/';
    private static final int BACKSLASH = '\\';
    private static final int MAX_PLAYER_NAME_LENGTH = 64;
    private static final String BEDROCK_PREFIX = "*";

    public GroupFileRecipient {
        originalValue = CampaignRecipientKey.normalizeOriginalValue(originalValue);
        Objects.requireNonNull(recipientKey, "recipientKey");
        if (recipientKey.playerUuidKey()) {
            if (explicitPlayerId == null || !recipientKey.playerUuid().equals(explicitPlayerId)) {
                throw new IllegalArgumentException("UUID recipient key must match explicitPlayerId");
            }
        } else if (explicitPlayerId != null) {
            throw new IllegalArgumentException("Name recipient must not have an explicit UUID");
        }
    }

    public static GroupFileRecipient parse(String value) {
        String original = CampaignRecipientKey.normalizeOriginalValue(value);
        UUID uuid = parseCanonicalUuid(original);
        if (uuid != null) {
            return new GroupFileRecipient(
                    original, CampaignRecipientKey.forPlayer(uuid), uuid);
        }
        if (looksLikeMalformedUuid(original)) {
            throw new IllegalArgumentException("malformed UUID recipient: " + original);
        }
        validateName(original);
        return new GroupFileRecipient(
                original, CampaignRecipientKey.forUnresolvedName(original), null);
    }

    private static UUID parseCanonicalUuid(String value) {
        if (!value.matches(
                "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            return null;
        }
        return UUID.fromString(value);
    }

    private static boolean looksLikeMalformedUuid(String value) {
        return value.length() == UUID_TEXT_LENGTH
                && value.charAt(UUID_FIRST_SEPARATOR) == UUID_SEPARATOR
                && value.charAt(UUID_SECOND_SEPARATOR) == UUID_SEPARATOR
                && value.charAt(UUID_THIRD_SEPARATOR) == UUID_SEPARATOR
                && value.charAt(UUID_FOURTH_SEPARATOR) == UUID_SEPARATOR;
    }

    private static void validateName(String value) {
        int start = value.startsWith(BEDROCK_PREFIX) ? BEDROCK_PREFIX.length() : 0;
        if (start == value.length()) {
            throw new IllegalArgumentException("player name must contain characters after '*'");
        }
        String name = value.substring(start);
        if (name.length() > MAX_PLAYER_NAME_LENGTH) {
            throw new IllegalArgumentException("player name exceeds 64 characters");
        }
        if (name.codePoints().anyMatch(GroupFileRecipient::unsupportedNameCodePoint)) {
            throw new IllegalArgumentException("player name contains unsupported characters");
        }
    }

    private static boolean unsupportedNameCodePoint(int codePoint) {
        return Character.isISOControl(codePoint)
                || codePoint == FORWARD_SLASH
                || codePoint == BACKSLASH;
    }
}
