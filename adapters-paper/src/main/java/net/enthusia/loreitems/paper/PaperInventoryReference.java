package net.enthusia.loreitems.paper;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;

/** Reload-safe reference to an inventory without retaining live Bukkit objects across ticks. */
sealed interface PaperInventoryReference
        permits PaperInventoryReference.PlayerMain,
                PaperInventoryReference.PlayerEnder,
                PaperInventoryReference.Block,
                PaperInventoryReference.DoubleChestBlock,
                PaperInventoryReference.EntityInventory {
    Optional<Inventory> resolve(Plugin plugin);

    static Optional<PaperInventoryReference> capture(Inventory inventory) {
        return inventory == null ? Optional.empty() : capture(inventory, inventory.getHolder(false));
    }

    /**
     * Use a holder already read by the caller, so Bukkit does not reconstruct
     * a block-state inventory holder twice during one event snapshot.
     */
    static Optional<PaperInventoryReference> capture(
            Inventory inventory, InventoryHolder holder) {
        if (inventory == null) {
            return Optional.empty();
        }
        if (holder instanceof Player player) {
            return Optional.of(inventory instanceof PlayerInventory
                    ? new PlayerMain(player.getUniqueId())
                    : new PlayerEnder(player.getUniqueId()));
        }
        if (holder instanceof Entity entity) {
            return Optional.of(new EntityInventory(entity.getUniqueId()));
        }
        return PaperDoubleChestIdentity.capture(inventory, holder);
    }

    static String blockKey(Location location) {
        return Objects.requireNonNull(location.getWorld(), "location world")
                        .getKey().toString()
                + ':' + location.getBlockX()
                + ':' + location.getBlockY()
                + ':' + location.getBlockZ();
    }

    record PlayerMain(UUID playerId) implements PaperInventoryReference {
        @Override
        public Optional<Inventory> resolve(Plugin plugin) {
            Player player = plugin.getServer().getPlayer(playerId);
            return Optional.ofNullable(player).map(Player::getInventory);
        }
    }

    record PlayerEnder(UUID playerId) implements PaperInventoryReference {
        @Override
        public Optional<Inventory> resolve(Plugin plugin) {
            Player player = plugin.getServer().getPlayer(playerId);
            return Optional.ofNullable(player).map(Player::getEnderChest);
        }
    }

    record Block(UUID worldId, int x, int y, int z) implements PaperInventoryReference {
        @Override
        public Optional<Inventory> resolve(Plugin plugin) {
            World world = plugin.getServer().getWorld(worldId);
            if (world == null || !world.isChunkLoaded(x >> 4, z >> 4)) {
                return Optional.empty();
            }
            BlockState state = world.getBlockAt(x, y, z).getState(false);
            if (state instanceof org.bukkit.block.Chest chest) {
                if (!(state.getBlockData() instanceof org.bukkit.block.data.type.Chest data)
                        || data.getType() != org.bukkit.block.data.type.Chest.Type.SINGLE) {
                    return Optional.empty();
                }
                Inventory inventory = chest.getInventory();
                return inventory.getSize() == 27 ? Optional.of(inventory) : Optional.empty();
            }
            return state instanceof InventoryHolder holder
                    ? Optional.of(holder.getInventory())
                    : Optional.empty();
        }
    }

    record DoubleChestBlock(
            UUID worldId,
            PaperDoubleChestIdentity.Position first,
            PaperDoubleChestIdentity.Position second,
            PaperDoubleChestIdentity.Position leftSide) implements PaperInventoryReference {
        public DoubleChestBlock {
            Objects.requireNonNull(worldId, "worldId");
            Objects.requireNonNull(first, "first");
            Objects.requireNonNull(second, "second");
            Objects.requireNonNull(leftSide, "leftSide");
            if (!leftSide.equals(first) && !leftSide.equals(second)) {
                throw new IllegalArgumentException("Left half must be part of the pair");
            }
            if (first.compareTo(second) >= 0 || !first.adjacent(second)) {
                throw new IllegalArgumentException("Double chest halves must be ordered neighbors");
            }
        }

        @Override
        public Optional<Inventory> resolve(Plugin plugin) {
            return PaperDoubleChestIdentity.resolve(
                    plugin.getServer().getWorld(worldId), this);
        }

        String stableKey() {
            return PaperDoubleChestIdentity.key(this);
        }
    }

    record EntityInventory(UUID entityId) implements PaperInventoryReference {
        @Override
        public Optional<Inventory> resolve(Plugin plugin) {
            Entity entity = plugin.getServer().getEntity(entityId);
            return entity instanceof InventoryHolder holder
                    ? Optional.of(holder.getInventory())
                    : Optional.empty();
        }
    }
}
