package net.enthusia.loreitems.paper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import net.enthusia.loreitems.application.EncodedItemTemplate;
import net.enthusia.loreitems.application.ItemCodecException;
import net.enthusia.loreitems.application.ItemIdentityReadResult;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.PreparedDirectDelivery;
import net.enthusia.loreitems.application.PreparedDistributionDelivery;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

public final class PaperDirectDeliveryOperator {
    private static final String SHA_256 = "SHA-256";
    private static final int SINGLE_ITEM_AMOUNT = 1;

    private final PaperItemTemplateCodec templateCodec;
    private final PaperItemIdentityCodec identityCodec;

    public PaperDirectDeliveryOperator() {
        this(new PaperItemTemplateCodec(), new PaperItemIdentityCodec());
    }

    PaperDirectDeliveryOperator(
            PaperItemTemplateCodec templateCodec,
            PaperItemIdentityCodec identityCodec) {
        this.templateCodec = Objects.requireNonNull(templateCodec, "templateCodec");
        this.identityCodec = Objects.requireNonNull(identityCodec, "identityCodec");
    }

    public ApplyResult apply(Player player, PreparedDirectDelivery delivery) {
        Objects.requireNonNull(delivery, "delivery");
        return apply(
                player,
                delivery.playerId(),
                delivery.identity(),
                delivery.template());
    }

    public ApplyResult apply(Player player, PreparedDistributionDelivery delivery) {
        Objects.requireNonNull(delivery, "delivery");
        return apply(
                player,
                delivery.playerId(),
                delivery.identity(),
                delivery.template());
    }

    public boolean hasStorageSpace(Player player) {
        Objects.requireNonNull(player, "player");
        return firstEmptyStorageSlot(player.getInventory()) >= 0;
    }

    private ApplyResult apply(
            Player player,
            UUID targetPlayerId,
            LoreItemIdentity identity,
            EncodedItemTemplate template) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(targetPlayerId, "targetPlayerId");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(template, "template");
        if (!player.getUniqueId().equals(targetPlayerId)) {
            return ApplyResult.reviewRequired(
                    "The claimed delivery belongs to another player.");
        }
        PlayerInventory inventory = player.getInventory();
        int emptySlot = firstEmptyStorageSlot(inventory);
        if (emptySlot < 0) {
            return ApplyResult.noSpace();
        }
        try {
            ItemStack decoded = templateCodec.decode(template);
            ItemStack tracked = identityCodec.writeIdentity(decoded, identity);
            inventory.setItem(emptySlot, tracked);
            ItemStack stored = inventory.getItem(emptySlot);
            if (!verifiedIdentity(stored, identity)) {
                inventory.setItem(emptySlot, null);
                return ApplyResult.reviewRequired(
                        "Paper did not retain the expected delivery identity in storage slot "
                                + emptySlot + "; the unverified insertion was removed.");
            }
            return ApplyResult.applied(
                    emptySlot,
                    fingerprint(Objects.requireNonNull(stored, "stored")));
        } catch (ItemCodecException | IllegalArgumentException exception) {
            return ApplyResult.reviewRequired(safeDetail(exception));
        } catch (RuntimeException exception) {
            return ApplyResult.reviewRequired(
                    "Paper failed while inserting or verifying the delivery: "
                            + exception.getClass().getSimpleName());
        }
    }

    private static int firstEmptyStorageSlot(PlayerInventory inventory) {
        ItemStack[] storage = inventory.getStorageContents();
        for (int slot = 0; slot < storage.length; slot++) {
            ItemStack item = storage[slot];
            if (item == null || item.getType().isAir()) {
                return slot;
            }
        }
        return -1;
    }

    private boolean verifiedIdentity(ItemStack item, LoreItemIdentity expectedIdentity) {
        if (item == null
                || item.getAmount() != SINGLE_ITEM_AMOUNT
                || item.getMaxStackSize() != SINGLE_ITEM_AMOUNT) {
            return false;
        }
        ItemIdentityReadResult result = identityCodec.readIdentity(item);
        return result instanceof ItemIdentityReadResult.Tracked tracked
                && expectedIdentity.equals(tracked.identity());
    }

    private static String fingerprint(ItemStack item) {
        try {
            MessageDigest digest = MessageDigest.getInstance(SHA_256);
            return HexFormat.of().formatHex(digest.digest(item.serializeAsBytes()));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Java runtime does not provide SHA-256", exception);
        }
    }

    private static String safeDetail(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }

    public record ApplyResult(
            Status status,
            Integer inventorySlot,
            String afterFingerprint,
            String detail) {
        public ApplyResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(detail, "detail");
            if (detail.isBlank()) {
                throw new IllegalArgumentException("detail must not be blank");
            }
            boolean applied = status == Status.APPLIED;
            if (applied != (inventorySlot != null && afterFingerprint != null)) {
                throw new IllegalArgumentException(
                        "Only APPLIED results contain a slot and fingerprint");
            }
        }

        public static ApplyResult applied(int inventorySlot, String afterFingerprint) {
            return new ApplyResult(
                    Status.APPLIED,
                    inventorySlot,
                    Objects.requireNonNull(afterFingerprint, "afterFingerprint"),
                    "The exact player-storage slot contains the verified tracked item.");
        }

        public static ApplyResult noSpace() {
            return new ApplyResult(
                    Status.NO_SPACE,
                    null,
                    null,
                    "The player inventory has no empty storage slot.");
        }

        public static ApplyResult reviewRequired(String detail) {
            return new ApplyResult(Status.REVIEW_REQUIRED, null, null, detail);
        }

        public enum Status {
            APPLIED,
            NO_SPACE,
            REVIEW_REQUIRED
        }
    }
}
