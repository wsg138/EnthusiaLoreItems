package net.enthusia.loreitems.paper;

import io.papermc.paper.event.player.PlayerPickBlockEvent;
import io.papermc.paper.event.player.PlayerPickEntityEvent;
import java.util.Objects;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.ItemFrame;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/** Detects creative-mode copy operations that could duplicate tracked identity evidence. */
final class PaperCreativeIdentityProtection {
    private static final EquipmentSlot[] ARMOR_STAND_SLOTS = {
        EquipmentSlot.HAND,
        EquipmentSlot.OFF_HAND,
        EquipmentSlot.FEET,
        EquipmentSlot.LEGS,
        EquipmentSlot.CHEST,
        EquipmentSlot.HEAD
    };

    private final PaperTrackedItemCollector itemCollector;

    PaperCreativeIdentityProtection(PaperTrackedItemCollector itemCollector) {
        this.itemCollector = Objects.requireNonNull(itemCollector, "itemCollector");
    }

    boolean shouldCancelClone(InventoryClickEvent event) {
        return event.getAction() == InventoryAction.CLONE_STACK
                && hasIdentityEvidenceInTree(event.getCurrentItem());
    }

    boolean shouldCancelInventoryMutation(InventoryCreativeEvent event) {
        return hasIdentityEvidenceInTree(event.getCurrentItem())
                || hasIdentityEvidenceInTree(event.getCursor());
    }

    boolean shouldCancelPickBlock(PlayerPickBlockEvent event) {
        return event.isIncludeData()
                && event.getBlock().getState() instanceof InventoryHolder holder
                && containsIdentityEvidenceInTree(holder.getInventory());
    }

    boolean shouldCancelPickEntity(PlayerPickEntityEvent event) {
        return entityContainsIdentityEvidence(event.getEntity());
    }

    private boolean containsIdentityEvidenceInTree(Inventory inventory) {
        for (ItemStack item : inventory.getContents()) {
            if (hasIdentityEvidenceInTree(item)) {
                return true;
            }
        }
        return false;
    }

    private boolean entityContainsIdentityEvidence(Entity entity) {
        if (entity instanceof Item item) {
            return hasIdentityEvidenceInTree(item.getItemStack());
        }
        if (entity instanceof ItemFrame frame) {
            return hasIdentityEvidenceInTree(frame.getItem());
        }
        if (entity instanceof ItemDisplay display) {
            return hasIdentityEvidenceInTree(display.getItemStack());
        }
        if (entity instanceof ArmorStand stand) {
            for (EquipmentSlot slot : ARMOR_STAND_SLOTS) {
                if (hasIdentityEvidenceInTree(stand.getEquipment().getItem(slot))) {
                    return true;
                }
            }
            return false;
        }
        return entity instanceof InventoryHolder holder
                && containsIdentityEvidenceInTree(holder.getInventory());
    }

    private boolean hasIdentityEvidenceInTree(ItemStack item) {
        return itemCollector.hasIdentityEvidence(item);
    }
}
