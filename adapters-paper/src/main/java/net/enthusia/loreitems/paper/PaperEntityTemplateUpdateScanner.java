package net.enthusia.loreitems.paper;

import java.util.Objects;
import net.enthusia.loreitems.application.ItemIdentityReadResult;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;

/** Reads root lore-item identities from supported already-loaded entities. */
class PaperEntityTemplateUpdateScanner {
    private final PaperItemIdentityCodec identityCodec = new PaperItemIdentityCodec();

    PaperTemplateUpdateScanner.Candidate scan(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        if (!entity.isValid() || entity.isDead()) {
            return null;
        }
        PaperEntityTemplateUpdateReference reference =
                PaperEntityTemplateUpdateReference.capture(entity);
        if (reference == null) {
            return null;
        }
        ItemStack item = read(reference, entity);
        if (item.getType().isAir()) {
            return null;
        }
        ItemIdentityReadResult result = identityCodec.readIdentity(item);
        if (!(result instanceof ItemIdentityReadResult.Tracked tracked)) {
            return null;
        }
        return new PaperTemplateUpdateScanner.Candidate(tracked.identity(), reference);
    }

    private static ItemStack read(
            PaperEntityTemplateUpdateReference reference,
            Entity entity) {
        return switch (reference.kind()) {
            case DROPPED_ITEM -> ((org.bukkit.entity.Item) entity).getItemStack();
            case ITEM_FRAME -> ((org.bukkit.entity.ItemFrame) entity).getItem();
            case ITEM_DISPLAY -> ((org.bukkit.entity.ItemDisplay) entity).getItemStack();
        };
    }
}
