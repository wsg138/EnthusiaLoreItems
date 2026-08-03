package net.enthusia.loreitems.paper;

import java.util.Objects;
import net.enthusia.loreitems.application.EncodedItemTemplate;
import net.enthusia.loreitems.application.ItemCodecException;
import net.enthusia.loreitems.application.ItemCodecFailure;
import net.enthusia.loreitems.application.ItemTemplateCodec;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public final class PaperItemTemplateCodec implements ItemTemplateCodec<ItemStack> {
    public static final int CURRENT_VERSION = 1;

    private final PaperItemCodecThreadGuard threadGuard;
    private final PaperItemIdentityCodec identityCodec;

    public PaperItemTemplateCodec() {
        this(PaperItemCodecThreadGuard.system());
    }

    PaperItemTemplateCodec(PaperItemCodecThreadGuard threadGuard) {
        this.threadGuard = Objects.requireNonNull(threadGuard, "threadGuard");
        this.identityCodec = new PaperItemIdentityCodec(threadGuard);
    }

    @Override
    public int currentVersion() {
        return CURRENT_VERSION;
    }

    @Override
    public EncodedItemTemplate encode(ItemStack item) {
        threadGuard.requirePrimaryThread();
        ItemStack normalized = normalize(identityCodec.clearIdentity(item));
        try {
            return new EncodedItemTemplate(CURRENT_VERSION, normalized.serializeAsBytes());
        } catch (RuntimeException exception) {
            throw new ItemCodecException(
                    ItemCodecFailure.PLATFORM_FAILURE,
                    "Paper failed to serialize the item template",
                    exception);
        }
    }

    @Override
    public ItemStack decode(EncodedItemTemplate encodedTemplate) {
        threadGuard.requirePrimaryThread();
        Objects.requireNonNull(encodedTemplate, "encodedTemplate");
        if (encodedTemplate.codecVersion() != CURRENT_VERSION) {
            throw new ItemCodecException(
                    ItemCodecFailure.UNSUPPORTED_VERSION,
                    "Unsupported item-template codec version " + encodedTemplate.codecVersion());
        }

        try {
            ItemStack decoded = ItemStack.deserializeBytes(encodedTemplate.payload());
            return normalize(identityCodec.clearIdentity(decoded));
        } catch (ItemCodecException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new ItemCodecException(
                    ItemCodecFailure.CORRUPT_PAYLOAD,
                    "Paper could not decode the item-template payload",
                    exception);
        }
    }

    private static ItemStack normalize(ItemStack item) {
        if (item.getType().isAir()) {
            throw new ItemCodecException(ItemCodecFailure.INVALID_ITEM, "Air cannot be an item template");
        }
        item.setAmount(1);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            throw new ItemCodecException(ItemCodecFailure.INVALID_ITEM, "Item metadata is unavailable");
        }
        meta.setMaxStackSize(1);
        if (!item.setItemMeta(meta)) {
            throw new ItemCodecException(ItemCodecFailure.PLATFORM_FAILURE, "Paper rejected item metadata");
        }
        return item;
    }
}
