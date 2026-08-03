package net.enthusia.loreitems.paper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import net.enthusia.loreitems.application.ItemCodecException;
import net.enthusia.loreitems.application.ItemCodecFailure;
import net.enthusia.loreitems.application.ItemIdentityReadResult;
import net.enthusia.loreitems.application.PrepareHeldItemAdoptionRequest;
import net.enthusia.loreitems.application.PreparedHeldItemAdoption;
import net.enthusia.loreitems.domain.DefinitionKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

public final class PaperHeldItemAdoptionOperator {
    private static final String SHA_256 = "SHA-256";
    private static final int SINGLE_ITEM_AMOUNT = 1;

    private final PaperItemIdentityCodec identityCodec;

    public PaperHeldItemAdoptionOperator() {
        this(new PaperItemIdentityCodec());
    }

    PaperHeldItemAdoptionOperator(PaperItemIdentityCodec identityCodec) {
        this.identityCodec = Objects.requireNonNull(identityCodec, "identityCodec");
    }

    public PrepareHeldItemAdoptionRequest snapshot(
            Player player,
            DefinitionKey definitionKey) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(definitionKey, "definitionKey");
        PlayerInventory inventory = player.getInventory();
        int selectedSlot = inventory.getHeldItemSlot();
        ItemStack heldItem = requireSingleUntrackedItem(inventory.getItem(selectedSlot));
        return new PrepareHeldItemAdoptionRequest(
                definitionKey,
                player.getUniqueId(),
                selectedSlot,
                fingerprint(heldItem));
    }

    public ApplyResult apply(
            Player player,
            PreparedHeldItemAdoption adoption) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(adoption, "adoption");
        if (!player.getUniqueId().equals(adoption.playerId())) {
            return ApplyResult.reviewRequired("The prepared adoption belongs to another player.");
        }
        PlayerInventory inventory = player.getInventory();
        if (inventory.getHeldItemSlot() != adoption.selectedSlot()) {
            return ApplyResult.reviewRequired("The selected hotbar slot changed before adoption.");
        }
        try {
            ItemStack current = requireSingleUntrackedItem(
                    inventory.getItem(adoption.selectedSlot()));
            if (!adoption.beforeFingerprint().equals(fingerprint(current))) {
                return ApplyResult.reviewRequired(
                        "The held item changed after durable adoption preparation.");
            }
            ItemStack tracked = identityCodec.writeIdentity(current, adoption.identity());
            inventory.setItem(adoption.selectedSlot(), tracked);
            ItemStack stored = inventory.getItem(adoption.selectedSlot());
            if (!verifiedIdentity(stored, adoption)) {
                return ApplyResult.reviewRequired(
                        "Paper did not retain the expected lore-item identity in the exact slot.");
            }
            return ApplyResult.applied(fingerprint(Objects.requireNonNull(stored, "stored")));
        } catch (ItemCodecException | IllegalArgumentException exception) {
            return ApplyResult.reviewRequired(safeDetail(exception));
        } catch (RuntimeException exception) {
            return ApplyResult.reviewRequired(
                    "Paper failed while applying or verifying the held-item identity: "
                            + exception.getClass().getSimpleName());
        }
    }

    private ItemStack requireSingleUntrackedItem(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            throw new ItemCodecException(
                    ItemCodecFailure.INVALID_ITEM,
                    "Hold one non-air item before adopting it.");
        }
        if (item.getAmount() != SINGLE_ITEM_AMOUNT) {
            throw new ItemCodecException(
                    ItemCodecFailure.INVALID_ITEM,
                    "Adoption requires exactly one held item; stacked items are preserved unchanged.");
        }
        ItemIdentityReadResult identity = identityCodec.readIdentity(item);
        if (identity instanceof ItemIdentityReadResult.Tracked) {
            throw new ItemCodecException(
                    ItemCodecFailure.INVALID_ITEM,
                    "The held item is already tracked and was not changed.");
        }
        if (identity instanceof ItemIdentityReadResult.Invalid invalid) {
            throw new ItemCodecException(
                    ItemCodecFailure.INVALID_ITEM,
                    "The held item has invalid lore-item identity and was preserved: "
                            + invalid.failure());
        }
        return item;
    }

    private boolean verifiedIdentity(
            ItemStack item,
            PreparedHeldItemAdoption adoption) {
        if (item == null || item.getAmount() != SINGLE_ITEM_AMOUNT
                || item.getMaxStackSize() != SINGLE_ITEM_AMOUNT) {
            return false;
        }
        ItemIdentityReadResult result = identityCodec.readIdentity(item);
        return result instanceof ItemIdentityReadResult.Tracked tracked
                && adoption.identity().equals(tracked.identity());
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
            String afterFingerprint,
            String detail) {
        public ApplyResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(detail, "detail");
            if (detail.isBlank()) {
                throw new IllegalArgumentException("detail must not be blank");
            }
            if ((status == Status.APPLIED) != (afterFingerprint != null)) {
                throw new IllegalArgumentException(
                        "Only APPLIED results may contain an after fingerprint");
            }
        }

        public static ApplyResult applied(String afterFingerprint) {
            return new ApplyResult(
                    Status.APPLIED,
                    Objects.requireNonNull(afterFingerprint, "afterFingerprint"),
                    "The exact held slot contains the verified tracked item.");
        }

        public static ApplyResult reviewRequired(String detail) {
            return new ApplyResult(Status.REVIEW_REQUIRED, null, detail);
        }

        public enum Status {
            APPLIED,
            REVIEW_REQUIRED
        }
    }
}
