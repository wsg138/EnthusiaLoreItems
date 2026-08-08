package net.enthusia.loreitems.paper;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import net.enthusia.loreitems.domain.LocationDescriptor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;

/** Converts reload-safe Paper references into the same durable location vocabulary as tracking. */
final class PaperDestructiveLocationResolver {
    private static final String PLAYER_PREFIX = "player:";
    private static final String SLOT_PREFIX = "slot:";

    private PaperDestructiveLocationResolver() {}

    static Optional<PaperTemplateUpdateReference.DestructiveLocation> resolve(
            Plugin plugin,
            PaperTemplateUpdateReference reference) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(reference, "reference");
        if (reference instanceof PaperTemplateUpdateItemReference item) {
            return resolveItem(plugin, item);
        }
        if (reference instanceof PaperEntityTemplateUpdateReference entity) {
            return resolveEntity(plugin, entity);
        }
        return Optional.empty();
    }

    private static Optional<PaperTemplateUpdateReference.DestructiveLocation> resolveItem(
            Plugin plugin,
            PaperTemplateUpdateItemReference reference) {
        RootLocation root = resolveRoot(plugin, reference.inventoryReference()).orElse(null);
        if (root == null) {
            return Optional.empty();
        }
        StringBuilder path = new StringBuilder(SLOT_PREFIX).append(reference.rootSlot());
        for (PaperTemplateUpdateItemReference.NestedStep step : reference.nestedPath()) {
            path.append('/')
                    .append(step.kind().name().toLowerCase(Locale.ROOT))
                    .append(':')
                    .append(step.index());
        }
        String type = reference.nestedPath().isEmpty()
                ? root.type().name()
                : LocationDescriptor.Type.NESTED_CONTAINER.name();
        return Optional.of(new PaperTemplateUpdateReference.DestructiveLocation(
                type, root.locationKey(), path.toString()));
    }

    private static Optional<RootLocation> resolveRoot(
            Plugin plugin,
            PaperInventoryReference reference) {
        if (reference instanceof PaperInventoryReference.PlayerMain player) {
            return Optional.of(new RootLocation(
                    LocationDescriptor.Type.PLAYER_INVENTORY,
                    PLAYER_PREFIX + player.playerId()));
        }
        if (reference instanceof PaperInventoryReference.PlayerEnder player) {
            return Optional.of(new RootLocation(
                    LocationDescriptor.Type.PLAYER_ENDER_CHEST,
                    PLAYER_PREFIX + player.playerId()));
        }
        if (reference instanceof PaperInventoryReference.Block block) {
            World world = plugin.getServer().getWorld(block.worldId());
            if (world == null) {
                return Optional.empty();
            }
            return Optional.of(new RootLocation(
                    LocationDescriptor.Type.BLOCK_CONTAINER,
                    world.getKey() + ":" + block.x() + ':' + block.y() + ':' + block.z()));
        }
        if (reference instanceof PaperInventoryReference.EntityInventory entityReference) {
            Entity entity = plugin.getServer().getEntity(entityReference.entityId());
            if (entity == null) {
                return Optional.empty();
            }
            return Optional.of(new RootLocation(
                    LocationDescriptor.Type.BLOCK_CONTAINER,
                    entity.getWorld().getKey() + ":entity:" + entity.getUniqueId()));
        }
        return Optional.empty();
    }

    private static Optional<PaperTemplateUpdateReference.DestructiveLocation> resolveEntity(
            Plugin plugin,
            PaperEntityTemplateUpdateReference reference) {
        Entity entity = plugin.getServer().getEntity(reference.entityId());
        if (entity == null || !entity.isValid() || entity.isDead()) {
            return Optional.empty();
        }
        Location location = entity.getLocation();
        String world = entity.getWorld().getKey().toString();
        return switch (reference.kind()) {
            case DROPPED_ITEM -> Optional.of(new PaperTemplateUpdateReference.DestructiveLocation(
                    LocationDescriptor.Type.DROPPED_ITEM.name(),
                    world + ":entity:" + entity.getUniqueId() + ':'
                            + location.getBlockX() + ':' + location.getBlockY() + ':' + location.getBlockZ(),
                    "item-entity"));
            case ITEM_FRAME -> Optional.of(displayLocation(
                    LocationDescriptor.Type.ITEM_FRAME, entity, location, world, "item"));
            case ITEM_DISPLAY -> Optional.of(displayLocation(
                    LocationDescriptor.Type.ITEM_DISPLAY, entity, location, world, "item"));
            case ARMOR_STAND -> Optional.of(displayLocation(
                    LocationDescriptor.Type.ARMOR_STAND,
                    entity,
                    location,
                    world,
                    armorStandPath(reference.equipmentSlot())));
        };
    }

    private static PaperTemplateUpdateReference.DestructiveLocation displayLocation(
            LocationDescriptor.Type type,
            Entity entity,
            Location location,
            String world,
            String path) {
        return new PaperTemplateUpdateReference.DestructiveLocation(
                type.name(),
                world + ':' + location.getBlockX() + ':' + location.getBlockY() + ':'
                        + location.getBlockZ() + ':' + entity.getUniqueId(),
                path);
    }

    private static String armorStandPath(EquipmentSlot slot) {
        Objects.requireNonNull(slot, "slot");
        return SLOT_PREFIX + slot.name().toLowerCase(Locale.ROOT);
    }

    private record RootLocation(LocationDescriptor.Type type, String locationKey) {
        private RootLocation {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(locationKey, "locationKey");
        }
    }
}
