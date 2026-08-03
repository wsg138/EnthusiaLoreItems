package net.enthusia.loreitems.application;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import net.enthusia.loreitems.domain.DefinitionKey;

public record PrepareHeldItemAdoptionRequest(
        DefinitionKey definitionKey,
        UUID playerId,
        int selectedSlot,
        String beforeFingerprint) {
    public static final int MINIMUM_HOTBAR_SLOT = 0;
    public static final int MAXIMUM_HOTBAR_SLOT = 8;

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public PrepareHeldItemAdoptionRequest {
        Objects.requireNonNull(definitionKey, "definitionKey");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(beforeFingerprint, "beforeFingerprint");
        if (selectedSlot < MINIMUM_HOTBAR_SLOT || selectedSlot > MAXIMUM_HOTBAR_SLOT) {
            throw new IllegalArgumentException("selectedSlot must identify a hotbar slot");
        }
        beforeFingerprint = beforeFingerprint.strip();
        if (!SHA_256.matcher(beforeFingerprint).matches()) {
            throw new IllegalArgumentException("beforeFingerprint must be a lowercase SHA-256 value");
        }
    }
}
