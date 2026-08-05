package net.enthusia.loreitems.paper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import org.bukkit.inventory.ItemStack;

final class PaperItemFingerprint {
    private static final String SHA_256 = "SHA-256";

    private PaperItemFingerprint() {
    }

    static String of(ItemStack item) {
        Objects.requireNonNull(item, "item");
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA_256);
            return HexFormat.of().formatHex(digest.digest(item.serializeAsBytes()));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Java runtime does not provide SHA-256", exception);
        }
    }
}
