package net.enthusia.loreitems.acceptance;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.bukkit.NamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Test-only event evidence. Never shipped in the production LoreItems JAR. */
final class WP05HopperEventProbe implements Listener {
    private static final NamespacedKey INSTANCE =
            Objects.requireNonNull(NamespacedKey.fromString("enthusialoreitems:instance_id"));
    private final JavaPlugin plugin;

    WP05HopperEventProbe(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent event) {
        byte[] identity = identity(event.getItem());
        if (identity == null) {
            return;
        }
        Inventory source = event.getSource();
        Inventory destination = event.getDestination();
        plugin.getLogger().info("WP05_ACCEPT HOPPER_EVENT source=" + source.getLocation()
                + " destination=" + destination.getLocation()
                + " moving=" + event.getItem().getType()
                + " source_size=" + source.getSize()
                + " destination_size=" + destination.getSize()
                + " event_codec=" + codecStatus(event.getItem()));
        // Query actual Bukkit inventories on the next primary-thread tick, after
        // the vanilla transfer commits. The event itself is not proof of arrival.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            List<Integer> sourceSlots = matchingSlots(source, identity);
            List<Integer> destinationSlots = matchingSlots(destination, identity);
            String settledCodec = destinationSlots.isEmpty()
                    ? "ABSENT" : codecStatus(destination.getItem(destinationSlots.getFirst()));
            plugin.getLogger().info("WP05_ACCEPT HOPPER_SETTLED"
                    + " destination_codec=" + settledCodec
                    + " source=" + source.getLocation()
                    + " destination=" + destination.getLocation()
                    + " source_slots=" + sourceSlots
                    + " destination_slots=" + destinationSlots
                    + " total_matching=" + (sourceSlots.size() + destinationSlots.size()));
        });
    }

    /** Consult the actual loaded production codec, not a test reimplementation. */
    private String codecStatus(ItemStack item) {
        try {
            org.bukkit.plugin.Plugin production = Objects.requireNonNull(
                    plugin.getServer().getPluginManager().getPlugin("EnthusiaLoreItems"));
            Class<?> codec = Class.forName(
                    "net.enthusia.loreitems.paper.PaperItemIdentityCodec",
                    true, production.getClass().getClassLoader());
            Object instance = codec.getConstructor().newInstance();
            Object result = codec.getMethod("readIdentity", ItemStack.class)
                    .invoke(instance, item);
            return result.getClass().getSimpleName();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("production codec diagnostic failed", exception);
        }
    }

    private static byte[] identity(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return null;
        }
        return stack.getItemMeta().getPersistentDataContainer()
                .get(INSTANCE, PersistentDataType.BYTE_ARRAY);
    }

    private static List<Integer> matchingSlots(Inventory inventory, byte[] identity) {
        List<Integer> slots = new ArrayList<>();
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (Arrays.equals(identity(contents[slot]), identity)) {
                slots.add(slot);
            }
        }
        return slots;
    }
}
