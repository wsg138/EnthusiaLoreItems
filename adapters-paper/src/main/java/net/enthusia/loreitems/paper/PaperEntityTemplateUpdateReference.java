package net.enthusia.loreitems.paper;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.ItemFrame;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Immutable reference to a root item carried by an already-loaded entity. */
record PaperEntityTemplateUpdateReference(UUID entityId, Kind kind)
        implements PaperTemplateUpdateReference {

    PaperEntityTemplateUpdateReference {
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(kind, "kind");
    }

    static PaperEntityTemplateUpdateReference capture(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        Kind kind = Kind.from(entity);
        return kind == null ? null : new PaperEntityTemplateUpdateReference(entity.getUniqueId(), kind);
    }

    @Override
    public Optional<Resolved> resolve(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        Entity entity = plugin.getServer().getEntity(entityId);
        if (!usable(entity) || !kind.matches(entity)) {
            return Optional.empty();
        }
        ItemStack item = kind.read(entity);
        if (item.getType().isAir()) {
            return Optional.empty();
        }
        return Optional.of(new Resolved(entity, kind, item.clone()));
    }

    private static boolean usable(Entity entity) {
        return entity != null && entity.isValid() && !entity.isDead();
    }

    enum Kind {
        DROPPED_ITEM,
        ITEM_FRAME,
        ITEM_DISPLAY;

        private static Kind from(Entity entity) {
            if (entity instanceof Item) {
                return DROPPED_ITEM;
            }
            if (entity instanceof ItemFrame) {
                return ITEM_FRAME;
            }
            if (entity instanceof ItemDisplay) {
                return ITEM_DISPLAY;
            }
            return null;
        }

        private boolean matches(Entity entity) {
            return switch (this) {
                case DROPPED_ITEM -> entity instanceof Item;
                case ITEM_FRAME -> entity instanceof ItemFrame;
                case ITEM_DISPLAY -> entity instanceof ItemDisplay;
            };
        }

        private ItemStack read(Entity entity) {
            return switch (this) {
                case DROPPED_ITEM -> ((Item) entity).getItemStack();
                case ITEM_FRAME -> ((ItemFrame) entity).getItem();
                case ITEM_DISPLAY -> ((ItemDisplay) entity).getItemStack();
            };
        }

        private void write(Entity entity, ItemStack item) {
            ItemStack replacement = item.clone();
            switch (this) {
                case DROPPED_ITEM -> ((Item) entity).setItemStack(replacement);
                case ITEM_FRAME -> ((ItemFrame) entity).setItem(replacement, false);
                case ITEM_DISPLAY -> ((ItemDisplay) entity).setItemStack(replacement);
            }
        }
    }

    static final class Resolved implements PaperTemplateUpdateReference.Resolved {
        private final Entity entity;
        private final Kind kind;
        private final ItemStack originalItem;

        private Resolved(Entity entity, Kind kind, ItemStack originalItem) {
            this.entity = Objects.requireNonNull(entity, "entity");
            this.kind = Objects.requireNonNull(kind, "kind");
            this.originalItem = Objects.requireNonNull(originalItem, "originalItem");
        }

        @Override
        public ItemStack originalItem() {
            return originalItem.clone();
        }

        @Override
        public boolean replace(ItemStack replacement) {
            Objects.requireNonNull(replacement, "replacement");
            if (!usable(entity) || !kind.matches(entity)) {
                return false;
            }
            kind.write(entity, replacement);
            return true;
        }

        @Override
        public ItemStack readStored() {
            if (!usable(entity) || !kind.matches(entity)) {
                return null;
            }
            return kind.read(entity).clone();
        }

        @Override
        public boolean restore() {
            if (!usable(entity) || !kind.matches(entity)) {
                return false;
            }
            kind.write(entity, originalItem);
            ItemStack restored = readStored();
            return restored != null
                    && PaperItemFingerprint.of(restored)
                            .equals(PaperItemFingerprint.of(originalItem));
        }
    }
}
