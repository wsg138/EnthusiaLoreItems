package net.enthusia.loreitems.paper;

import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;

/** Pre-event evidence hint only; location is resolved from actual next-tick Bukkit state. */
final class PaperInventoryEventHint {
    private final PaperTrackedItemCollector collector;

    PaperInventoryEventHint(PaperTrackedItemCollector collector) {
        this.collector = Objects.requireNonNull(collector, "collector");
    }

    boolean relevant(InventoryClickEvent event, Player player) {
        if (conservativeAction(event)) {
            return true;
        }
        if (evidence(event.getCursor()) || evidence(event.getCurrentItem())) {
            return true;
        }
        int hotbar = event.getHotbarButton();
        if (hotbar >= 0 && hotbar < 9 && evidence(player.getInventory().getItem(hotbar))) {
            return true;
        }
        if (event.getClick() == ClickType.SWAP_OFFHAND
                && evidence(player.getInventory().getItemInOffHand())) {
            return true;
        }
        return event instanceof CraftItemEvent craft
                && evidence(craft.getRecipe().getResult());
    }

    private static boolean conservativeAction(InventoryClickEvent event) {
        return event.getClick() == ClickType.DOUBLE_CLICK
                || event.getAction() == InventoryAction.COLLECT_TO_CURSOR
                || event.getAction() == InventoryAction.UNKNOWN;
    }

    boolean relevant(InventoryDragEvent event) {
        if (evidence(event.getOldCursor()) || evidence(event.getCursor())) {
            return true;
        }
        for (ItemStack stack : event.getNewItems().values()) {
            if (evidence(stack)) {
                return true;
            }
        }
        return false;
    }

    private boolean evidence(ItemStack item) {
        return collector.hasIdentityEvidence(item);
    }
}
