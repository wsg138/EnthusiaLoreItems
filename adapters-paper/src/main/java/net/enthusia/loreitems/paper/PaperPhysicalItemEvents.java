package net.enthusia.loreitems.paper;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/**
 * Paper-thread inventory pickup, drop, spawn and death movement handlers.
 * Lifecycle event listener entrypoints remain registered on the main tracking listener.
 */
final class PaperPhysicalItemEvents {
    private static final int MAX_ITEMS_PER_SCAN = 256;

    private final Plugin plugin;
    private final PaperPhysicalInventoryScanner scanner;
    private final PaperDeferredMainThreadActions deferredActions;
    private final BiConsumer<UUID, String> schedulePlayer;
    private final Set<UUID> deathDrops = new HashSet<>();

    PaperPhysicalItemEvents(
            Plugin plugin, PaperPhysicalInventoryScanner scanner,
            PaperDeferredMainThreadActions deferredActions,
            BiConsumer<UUID, String> schedulePlayer) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.scanner = Objects.requireNonNull(scanner, "scanner");
        this.deferredActions = Objects.requireNonNull(deferredActions, "deferredActions");
        this.schedulePlayer = Objects.requireNonNull(schedulePlayer, "schedulePlayer");
    }

    void onInventoryPickup(InventoryPickupItemEvent event) {
        Item item = event.getItem();
        ItemStack stack = item.getItemStack();
        scanner.scanItemTree(
                stack,
                PaperPhysicalEntityScanner.droppedLocation(item),
                TrackingObservationUseCase.Presence.LAST_CONFIRMED,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                "hopper-pickup-source",
                new PaperScanLimit(MAX_ITEMS_PER_SCAN));
        Optional<PaperPhysicalInventorySnapshot> destination =
                PaperPhysicalInventorySnapshot.capture(event.getInventory());
        Set<LoreItemIdentity> identities = scanner.trackedIdentities(stack);
        scheduleNextTick(() -> {
            submitMatchingIdentities(destination, identities, "hopper-pickup-destination");
            scanReference(destination, "hopper-pickup-destination");
        });
    }


    void onDrop(PlayerDropItemEvent event) {
        Item item = event.getItemDrop();
        scanner.scanItemTree(
                item.getItemStack(),
                PaperPhysicalEntityScanner.droppedLocation(item),
                TrackingObservationUseCase.Presence.PRESENT,
                TrackingObservationUseCase.EvidenceMode.AUTHORITATIVE_TRANSITION,
                "player-drop",
                new PaperScanLimit(MAX_ITEMS_PER_SCAN));
        schedulePlayerUnique(event.getPlayer().getUniqueId(), "player-drop-player");
    }


    void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        Item item = event.getItem();
        scanner.scanItemTree(
                item.getItemStack(),
                PaperPhysicalEntityScanner.droppedLocation(item),
                TrackingObservationUseCase.Presence.LAST_CONFIRMED,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                "player-pickup-source",
                new PaperScanLimit(MAX_ITEMS_PER_SCAN));
        schedulePlayerUnique(player.getUniqueId(), "player-pickup-destination");
    }


    void onItemSpawn(ItemSpawnEvent event) {
        Item item = event.getEntity();
        Set<LoreItemIdentity> identities = scanner.trackedIdentities(item.getItemStack());
        boolean deathDrop = false;
        for (LoreItemIdentity identity : identities) {
            if (deathDrops.remove(identity.instanceId().value())) {
                deathDrop = true;
            }
        }
        TrackingObservationUseCase.EvidenceMode mode = deathDrop
                ? TrackingObservationUseCase.EvidenceMode.AUTHORITATIVE_TRANSITION
                : TrackingObservationUseCase.EvidenceMode.RECONCILIATION;
        scanner.scanItemTree(
                item.getItemStack(),
                PaperPhysicalEntityScanner.droppedLocation(item),
                TrackingObservationUseCase.Presence.PRESENT,
                mode,
                "item-spawn",
                new PaperScanLimit(MAX_ITEMS_PER_SCAN));
    }


    void onDeath(PlayerDeathEvent event) {
        int remaining = MAX_ITEMS_PER_SCAN;
        for (ItemStack item : event.getDrops()) {
            if (remaining <= 0) {
                break;
            }
            remaining--;
            for (LoreItemIdentity identity : scanner.trackedIdentities(item)) {
                deathDrops.add(identity.instanceId().value());
            }
        }
        scanner.scanPlayer(
                event.getEntity(),
                TrackingObservationUseCase.Presence.LAST_CONFIRMED,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                "player-death");
        scheduleNextTick(deathDrops::clear);
    }


    void scanReference(
            Optional<PaperPhysicalInventorySnapshot> snapshot, String source) {
        snapshot.ifPresent(reference -> reference.resolve(plugin).ifPresent(inventory ->
                scanner.scanInventory(
                        inventory,
                        reference.type(),
                        reference.key(),
                        TrackingObservationUseCase.Presence.PRESENT,
                        TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                        source)));
    }

    void submitMatchingIdentities(
            Optional<PaperPhysicalInventorySnapshot> snapshot,
            Set<LoreItemIdentity> identities,
            String source) {
        if (identities.isEmpty()) {
            return;
        }
        snapshot.ifPresent(reference -> reference.resolve(plugin).ifPresent(inventory ->
                scanner.submitMatchingIdentities(
                        inventory,
                        reference.type(),
                        reference.key(),
                        identities,
                        source)));
    }


    boolean scanPlayer(UUID playerId, boolean unique, String source) {
        Player player = plugin.getServer().getPlayer(playerId);
        if (player == null) {
            return false;
        }
        if (unique) {
            scanner.scanPlayerUnique(player, source);
        } else {
            scanner.scanPlayer(
                    player,
                    TrackingObservationUseCase.Presence.PRESENT,
                    TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                    source);
        }
        return true;
    }

    private void schedulePlayerUnique(UUID playerId, String source) {
        schedulePlayer.accept(playerId, source);
    }

    private void scheduleNextTick(Runnable action) {
        deferredActions.schedule(action);
    }

    void clear() {
        deathDrops.clear();
    }
}
