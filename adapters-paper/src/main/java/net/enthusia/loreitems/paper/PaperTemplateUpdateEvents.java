package net.enthusia.loreitems.paper;

import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Paper event adapter for naturally accessible inventory-backed template updates. */
final class PaperTemplateUpdateEvents implements Listener {
    private final PaperTemplateUpdateListener listener;

    PaperTemplateUpdateEvents(PaperTemplateUpdateListener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        listener.enqueuePlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        listener.forget(new PaperInventoryReference.PlayerMain(player.getUniqueId()));
        listener.forget(new PaperInventoryReference.PlayerEnder(player.getUniqueId()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSlotChange(PlayerInventorySlotChangeEvent event) {
        listener.enqueue(new PaperInventoryReference.PlayerMain(
                event.getPlayer().getUniqueId()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            listener.scheduleViewRescan(player, event.getView().getTopInventory());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            listener.scheduleViewRescan(player, event.getView().getTopInventory());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        listener.enqueuePlayer(player);
        PaperInventoryReference.capture(event.getView().getTopInventory())
                .ifPresent(listener::enqueue);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        Optional<PaperInventoryReference> source =
                PaperInventoryReference.capture(event.getSource());
        Optional<PaperInventoryReference> destination =
                PaperInventoryReference.capture(event.getDestination());
        source.ifPresent(listener::invalidate);
        destination.ifPresent(listener::invalidate);
        listener.scheduleNextTick(() -> {
            source.ifPresent(listener::enqueue);
            destination.ifPresent(listener::enqueue);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryPickup(InventoryPickupItemEvent event) {
        Optional<PaperInventoryReference> inventory =
                PaperInventoryReference.capture(event.getInventory());
        inventory.ifPresent(listener::invalidate);
        listener.scheduleNextTick(() -> inventory.ifPresent(listener::enqueue));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            PaperInventoryReference.PlayerMain main =
                    new PaperInventoryReference.PlayerMain(player.getUniqueId());
            listener.invalidate(main);
            listener.scheduleNextTick(() -> listener.enqueue(main));
        }
    }
}
