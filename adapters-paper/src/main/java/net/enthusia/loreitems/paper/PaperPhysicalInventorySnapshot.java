package net.enthusia.loreitems.paper;

import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;

/** Immutable reference and durable location identity for a physical inventory. */
record PaperPhysicalInventorySnapshot(
        PaperInventoryReference reference,
        net.enthusia.loreitems.domain.LocationDescriptor.Type type,
        String key) {
    static Optional<PaperPhysicalInventorySnapshot> capture(Inventory inventory) {
        if (inventory == null) {
            return Optional.empty();
        }
        // CraftInventory.getHolder() can deserialize a block-state snapshot.
        // Capture it once and reuse it for both the reload-safe reference and
        // its durable location descriptor; never retain the Bukkit holder.
        InventoryHolder holder = inventory.getHolder(false);
        Optional<PaperInventoryReference> reference =
                PaperInventoryReference.capture(inventory, holder);
        if (reference.isEmpty()) {
            return Optional.empty();
        }
        if (holder instanceof Player player) {
            boolean main = inventory instanceof PlayerInventory;
            return Optional.of(new PaperPhysicalInventorySnapshot(
                    reference.orElseThrow(),
                    main
                            ? net.enthusia.loreitems.domain.LocationDescriptor.Type.PLAYER_INVENTORY
                            : net.enthusia.loreitems.domain.LocationDescriptor.Type.PLAYER_ENDER_CHEST,
                    "player:" + player.getUniqueId()));
        }
        if (holder instanceof Entity entity) {
            return Optional.of(new PaperPhysicalInventorySnapshot(
                    reference.orElseThrow(),
                    net.enthusia.loreitems.domain.LocationDescriptor.Type.BLOCK_CONTAINER,
                    entity.getWorld().getKey() + ":entity:" + entity.getUniqueId()));
        }
        return captureBlock(inventory, reference.orElseThrow());
    }

    private static Optional<PaperPhysicalInventorySnapshot> captureBlock(
            Inventory inventory, PaperInventoryReference reference) {
        if (reference instanceof PaperInventoryReference.DoubleChestBlock pair) {
            return Optional.of(new PaperPhysicalInventorySnapshot(
                    pair,
                    net.enthusia.loreitems.domain.LocationDescriptor.Type.BLOCK_CONTAINER,
                    pair.stableKey()));
        }
        Location location = inventory.getLocation();
        if (location == null || location.getWorld() == null) {
            return Optional.empty();
        }
        return Optional.of(new PaperPhysicalInventorySnapshot(
                reference,
                net.enthusia.loreitems.domain.LocationDescriptor.Type.BLOCK_CONTAINER,
                PaperInventoryReference.blockKey(location)));
    }

    Optional<Inventory> resolve(Plugin plugin) {
        return reference.resolve(plugin);
    }

    /**
     * Capture and resolve the authoritative inventory as one operation.
     * A paired 27-slot block inventory is NEVER safe to scan with the
     * canonical 54-slot double-chest key; resolve it to the verified merged
     * inventory before constructing any slot paths.
     */
    static Optional<ResolvedView> captureResolved(Inventory source, Plugin plugin) {
        return capture(source).flatMap(snapshot -> snapshot.resolve(plugin)
                .filter(snapshot::hasCanonicalShape)
                .map(inventory -> new ResolvedView(snapshot, inventory)));
    }

    private boolean hasCanonicalShape(Inventory inventory) {
        if (reference instanceof PaperInventoryReference.DoubleChestBlock) {
            return inventory instanceof org.bukkit.inventory.DoubleChestInventory
                    && inventory.getSize() == 54;
        }
        return true;
    }

    record ResolvedView(PaperPhysicalInventorySnapshot snapshot, Inventory inventory) {}
}
