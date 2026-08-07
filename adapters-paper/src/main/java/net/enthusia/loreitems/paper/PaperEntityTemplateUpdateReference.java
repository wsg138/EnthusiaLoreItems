package net.enthusia.loreitems.paper;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.ItemFrame;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Immutable reference to an item carried by an already-loaded entity. */
record PaperEntityTemplateUpdateReference(
        UUID entityId,
        Kind kind,
        EquipmentSlot equipmentSlot)
        implements PaperTemplateUpdateReference {

    PaperEntityTemplateUpdateReference {
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(kind, "kind");
        if (kind == Kind.ARMOR_STAND) {
            requireArmorStandSlot(equipmentSlot);
        } else if (equipmentSlot != null) {
            throw new IllegalArgumentException("equipmentSlot is only valid for armor stands");
        }
    }

    PaperEntityTemplateUpdateReference(UUID entityId, Kind kind) {
        this(entityId, kind, null);
    }

    static PaperEntityTemplateUpdateReference capture(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        Kind kind = Kind.fromRoot(entity);
        return kind == null
                ? null
                : new PaperEntityTemplateUpdateReference(entity.getUniqueId(), kind);
    }

    static PaperEntityTemplateUpdateReference armorStand(
            ArmorStand armorStand,
            EquipmentSlot equipmentSlot) {
        Objects.requireNonNull(armorStand, "armorStand");
        return new PaperEntityTemplateUpdateReference(
                armorStand.getUniqueId(), Kind.ARMOR_STAND, equipmentSlot);
    }

    static boolean supports(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        return entity instanceof ArmorStand || Kind.fromRoot(entity) != null;
    }

    @Override
    public DestructiveLocation destructiveLocation() {
        return new DestructiveLocation(
                kind.name(),
                entityId.toString(),
                equipmentSlot == null ? null : "slot=" + equipmentSlot.name());
    }

    @Override
    public Optional<Resolved> resolve(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        Entity entity = plugin.getServer().getEntity(entityId);
        if (!usable(entity) || !kind.matches(entity)) {
            return Optional.empty();
        }
        ItemStack item = read(entity);
        if (item.getType().isAir()) {
            return Optional.empty();
        }
        return Optional.of(new Resolved(entity, kind, equipmentSlot, item.clone()));
    }

    ItemStack read(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        if (kind == Kind.ARMOR_STAND) {
            return equipment(entity).getItem(equipmentSlot);
        }
        return kind.readRoot(entity);
    }

    private void write(Entity entity, ItemStack item) {
        ItemStack replacement = item.clone();
        if (kind == Kind.ARMOR_STAND) {
            equipment(entity).setItem(equipmentSlot, replacement, true);
        } else {
            kind.writeRoot(entity, replacement);
        }
    }

    private void remove(Entity entity) {
        if (kind == Kind.DROPPED_ITEM) {
            entity.remove();
            return;
        }
        write(entity, new ItemStack(Material.AIR));
    }

    private static EntityEquipment equipment(Entity entity) {
        return Objects.requireNonNull(
                ((ArmorStand) entity).getEquipment(),
                "armor stand equipment");
    }

    private static void requireArmorStandSlot(EquipmentSlot equipmentSlot) {
        Objects.requireNonNull(equipmentSlot, "equipmentSlot");
        if (equipmentSlot == EquipmentSlot.BODY || equipmentSlot == EquipmentSlot.SADDLE) {
            throw new IllegalArgumentException("unsupported armor stand equipment slot");
        }
    }

    private static boolean usable(Entity entity) {
        return entity != null && entity.isValid() && !entity.isDead();
    }

    enum Kind {
        DROPPED_ITEM,
        ITEM_FRAME,
        ITEM_DISPLAY,
        ARMOR_STAND;

        private static Kind fromRoot(Entity entity) {
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
                case ARMOR_STAND -> entity instanceof ArmorStand;
            };
        }

        private ItemStack readRoot(Entity entity) {
            return switch (this) {
                case DROPPED_ITEM -> ((Item) entity).getItemStack();
                case ITEM_FRAME -> ((ItemFrame) entity).getItem();
                case ITEM_DISPLAY -> ((ItemDisplay) entity).getItemStack();
                case ARMOR_STAND -> throw new IllegalStateException("armor stand slot required");
            };
        }

        private void writeRoot(Entity entity, ItemStack item) {
            switch (this) {
                case DROPPED_ITEM -> ((Item) entity).setItemStack(item);
                case ITEM_FRAME -> ((ItemFrame) entity).setItem(item, false);
                case ITEM_DISPLAY -> ((ItemDisplay) entity).setItemStack(item);
                case ARMOR_STAND -> throw new IllegalStateException("armor stand slot required");
            }
        }
    }

    static final class Resolved implements PaperTemplateUpdateReference.Resolved {
        private final Entity entity;
        private final Kind kind;
        private final EquipmentSlot equipmentSlot;
        private final ItemStack originalItem;

        private Resolved(
                Entity entity,
                Kind kind,
                EquipmentSlot equipmentSlot,
                ItemStack originalItem) {
            this.entity = Objects.requireNonNull(entity, "entity");
            this.kind = Objects.requireNonNull(kind, "kind");
            this.equipmentSlot = equipmentSlot;
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
            reference().write(entity, replacement);
            return true;
        }

        @Override
        public boolean remove() {
            if (!usable(entity) || !kind.matches(entity)) {
                return false;
            }
            PaperEntityTemplateUpdateReference reference = reference();
            ItemStack current = reference.read(entity);
            if (current.getType().isAir()
                    || !PaperItemFingerprint.of(current)
                            .equals(PaperItemFingerprint.of(originalItem))) {
                return false;
            }
            reference.remove(entity);
            return true;
        }

        @Override
        public ItemStack readStored() {
            if (!usable(entity) || !kind.matches(entity)) {
                return null;
            }
            ItemStack stored = reference().read(entity);
            return stored.getType().isAir() ? null : stored.clone();
        }

        @Override
        public boolean restore() {
            if (!usable(entity) || !kind.matches(entity)) {
                return false;
            }
            reference().write(entity, originalItem);
            ItemStack restored = readStored();
            return restored != null
                    && PaperItemFingerprint.of(restored)
                            .equals(PaperItemFingerprint.of(originalItem));
        }

        private PaperEntityTemplateUpdateReference reference() {
            return new PaperEntityTemplateUpdateReference(
                    entity.getUniqueId(), kind, equipmentSlot);
        }
    }
}
