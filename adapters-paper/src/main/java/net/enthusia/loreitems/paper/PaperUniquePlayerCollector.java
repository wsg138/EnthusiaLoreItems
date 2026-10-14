package net.enthusia.loreitems.paper;

import java.util.List;
import java.util.Map;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.domain.LocationDescriptor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/** Bounded, exact-slot inventory/cursor evidence collection for one online player. */
final class PaperUniquePlayerCollector {
    private static final String SLOT_PREFIX = "slot:";
    private static final ItemStack[] EMPTY_CONTENTS = new ItemStack[0];
    private final PaperTrackedItemCollector collector;

    PaperUniquePlayerCollector(PaperTrackedItemCollector collector) {
        this.collector = collector;
    }

    void collectPlayer(
            Player player,
            Map<LoreItemIdentity, List<LocationDescriptor>> observations,
            PaperScanLimit limit) {
        String key = "player:" + player.getUniqueId();
        PlayerInventory inventory = player.getInventory();
        collector.collectArray(
                contentsOrEmpty(inventory.getStorageContents()),
                LocationDescriptor.Type.PLAYER_INVENTORY,
                key,
                SLOT_PREFIX,
                observations,
                limit);
        collector.collectArray(
                contentsOrEmpty(inventory.getArmorContents()),
                LocationDescriptor.Type.PLAYER_INVENTORY,
                key,
                "armor:",
                observations,
                limit);
        collector.collectItem(
                inventory.getItemInOffHand(),
                LocationDescriptor.Type.PLAYER_INVENTORY,
                key,
                "offhand",
                observations,
                0,
                limit);
        collector.collectItem(
                player.getItemOnCursor(),
                LocationDescriptor.Type.PLAYER_INVENTORY,
                key,
                "cursor",
                observations,
                0,
                limit);
        collector.collectArray(
                contentsOrEmpty(player.getEnderChest().getContents()),
                LocationDescriptor.Type.PLAYER_ENDER_CHEST,
                key,
                SLOT_PREFIX,
                observations,
                limit);
    }


    private static ItemStack[] contentsOrEmpty(ItemStack[] contents) {
        return contents == null ? EMPTY_CONTENTS : contents;
    }
}
