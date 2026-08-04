package net.enthusia.loreitems.paper;

import java.util.Objects;
import net.enthusia.loreitems.application.EncodedItemTemplate;
import net.enthusia.loreitems.application.ItemCodecException;
import net.enthusia.loreitems.application.ItemCodecFailure;
import net.enthusia.loreitems.application.ItemIdentityReadResult;
import org.bukkit.inventory.ItemStack;

public final class PaperHeldItemDefinitionSnapshotter {
    private final PaperItemIdentityCodec identityCodec;
    private final PaperItemTemplateCodec templateCodec;

    public PaperHeldItemDefinitionSnapshotter() {
        this(new PaperItemIdentityCodec(), new PaperItemTemplateCodec());
    }

    PaperHeldItemDefinitionSnapshotter(
            PaperItemIdentityCodec identityCodec,
            PaperItemTemplateCodec templateCodec) {
        this.identityCodec = Objects.requireNonNull(identityCodec, "identityCodec");
        this.templateCodec = Objects.requireNonNull(templateCodec, "templateCodec");
    }

    public EncodedItemTemplate snapshot(ItemStack heldItem) {
        Objects.requireNonNull(heldItem, "heldItem");
        ItemIdentityReadResult identity = identityCodec.readIdentity(heldItem);
        if (identity instanceof ItemIdentityReadResult.Invalid invalid) {
            throw new ItemCodecException(
                    ItemCodecFailure.INVALID_ITEM,
                    "Held item has invalid lore-item identity: " + invalid.failure());
        }
        return templateCodec.encode(heldItem);
    }
}
