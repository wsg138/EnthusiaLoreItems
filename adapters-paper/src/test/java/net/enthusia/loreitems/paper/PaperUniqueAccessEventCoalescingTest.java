package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.ShulkerBox;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;
import net.kyori.adventure.text.Component;

class PaperUniqueAccessEventCoalescingTest {
    private static final LoreItemIdentity IDENTITY = new LoreItemIdentity(
            new LoreDefinitionId(UUID.fromString("11111111-1111-1111-1111-111111111111")),
            new LoreInstanceId(UUID.fromString("22222222-2222-2222-2222-222222222222")),
            new TemplateRevision(1));

    private ServerMock server;
    private PluginMock plugin;
    private PlayerMock player;
    private CountingMetrics metrics;
    private List<TrackingObservationUseCase.Request> observed;
    private PaperUniqueAccessTrackingListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        player = server.addPlayer("alice");
        metrics = new CountingMetrics();
        observed = new ArrayList<>();
        listener = new PaperUniqueAccessTrackingListener(plugin, () -> request -> {
            observed.add(request);
            return CompletableFuture.completedFuture(TrackingObservationUseCase.Result.of(
                    TrackingObservationUseCase.Status.RECORDED, "ok"));
        }, () -> 4, metrics);
    }

    @AfterEach
    void tearDown() {
        listener.close();
        MockBukkit.unmock();
    }

    @Test
    void aThousandOrdinaryClicksDoNotScheduleReconciliation() {
        Inventory top = server.createInventory(null, 9);
        top.setItem(0, ItemStack.of(Material.STONE));
        player.openInventory(top);
        InventoryClickEvent click = click(ClickType.LEFT, InventoryAction.PICKUP_ALL);
        for (int index = 0; index < 1000; index++) {
            listener.onClick(click);
        }
        assertEquals(1000, metrics.count("tracking.unique.plain_click_skipped"));
        assertEquals(0, metrics.count("tracking.unique.access_scheduled"));
        server.getScheduler().performOneTick();
        assertTrue(observed.isEmpty());
    }

    @Test
    void oneThousandTrackedClicksScheduleOnlyOnePostEventPass() {
        player.openInventory(server.createInventory(null, 9));
        player.setItemOnCursor(trackedItem());
        InventoryClickEvent click = click(ClickType.LEFT, InventoryAction.PLACE_ALL);
        for (int index = 0; index < 1000; index++) {
            listener.onClick(click);
        }
        assertEquals(1, metrics.count("tracking.unique.access_scheduled"));
        assertEquals(999, metrics.count("tracking.unique.access_coalesced"));
        player.setItemOnCursor(ItemStack.empty());
        player.getInventory().setItem(7, trackedItem());
        server.getScheduler().performOneTick();
        assertEquals(1, observed.size());
        assertEquals("slot:7", observed.getFirst().location().containerPath());
        // Synthetic top inventory has no reload-safe reference. Never claim global uniqueness.
        assertEquals(TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                observed.getFirst().mode());
    }

    @Test
    void duplicatedCursorAndPlayerSlotRemainTwoReconciliationLocations() {
        player.openInventory(server.createInventory(null, 9));
        player.setItemOnCursor(trackedItem());
        listener.onClick(click(ClickType.LEFT, InventoryAction.PLACE_ALL));
        player.getInventory().setItem(3, trackedItem());
        server.getScheduler().performOneTick();
        assertEquals(2, observed.size());
        assertTrue(observed.stream().anyMatch(r ->
                r.location().containerPath().equals("cursor")));
        assertTrue(observed.stream().anyMatch(r ->
                r.location().containerPath().equals("slot:3")));
        assertTrue(observed.stream().allMatch(r ->
                r.mode() == TrackingObservationUseCase.EvidenceMode.RECONCILIATION));
    }

    @Test
    void cancelledClickUsesOnlyActualPostEventState() {
        player.openInventory(server.createInventory(null, 9));
        player.setItemOnCursor(trackedItem());
        InventoryClickEvent event = click(ClickType.LEFT, InventoryAction.PLACE_ALL);
        event.setCancelled(true);
        listener.onClick(event);
        server.getScheduler().performOneTick();
        assertEquals(1, observed.size());
        assertEquals("cursor", observed.getFirst().location().containerPath());
    }

    @Test
    void dragAcrossMultipleSlotsCoalescesWithClick() {
        player.openInventory(server.createInventory(null, 9));
        player.setItemOnCursor(trackedItem());
        listener.onClick(click(ClickType.LEFT, InventoryAction.PLACE_ALL));
        InventoryDragEvent drag = new InventoryDragEvent(
                player.getOpenInventory(), ItemStack.empty(), trackedItem(),
                false, Map.of(0, trackedItem(), 1, trackedItem()));
        listener.onDrag(drag);
        player.setItemOnCursor(ItemStack.empty());
        player.getInventory().setItem(2, trackedItem());
        server.getScheduler().performOneTick();
        assertEquals(1, metrics.count("tracking.unique.access_scheduled"));
        assertEquals(1, metrics.count("tracking.unique.access_coalesced"));
        assertEquals("slot:2", observed.getFirst().location().containerPath());
    }

    @Test
    void offhandSwapIsDetectedWithoutCursorOrClickedItemEvidence() {
        player.openInventory(server.createInventory(null, 9));
        player.getInventory().setItemInOffHand(trackedItem());
        listener.onClick(click(ClickType.SWAP_OFFHAND, InventoryAction.HOTBAR_SWAP));
        server.getScheduler().performOneTick();
        assertEquals(1, metrics.count("tracking.unique.access_scheduled"));
        assertEquals("offhand", observed.getFirst().location().containerPath());
    }

    @Test
    void quitSupersedesPendingLiveAccessWithoutReconfirmingPresence() {
        player.openInventory(server.createInventory(null, 9));
        player.setItemOnCursor(trackedItem());
        listener.onClick(click(ClickType.LEFT, InventoryAction.PLACE_ALL));
        listener.onQuit(new PlayerQuitEvent(
                player, (Component) null, PlayerQuitEvent.QuitReason.DISCONNECTED));
        server.getScheduler().performOneTick();
        assertEquals(2, observed.size());
        assertEquals(TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                observed.getFirst().mode());
        assertEquals(TrackingObservationUseCase.Presence.LAST_CONFIRMED,
                observed.getLast().presence());
        assertEquals(1, metrics.count("tracking.unique.access_drained_on_quit"));
    }

    @Test
    void cursorToResolvedEnderChestSlotToPlayerSlotToCursorUsesFinalLocations() {
        Inventory chest = player.getEnderChest();
        var reference = PaperInventoryReference.capture(chest);
        assertTrue(reference.isPresent(), "ender chest lacks inventory reference");
        assertTrue(reference.orElseThrow().resolve(plugin).isPresent(),
                "ender chest reference did not resolve");
        player.openInventory(chest);
        player.setItemOnCursor(trackedItem());

        listener.onClick(click(ClickType.LEFT, InventoryAction.PLACE_ALL));
        player.setItemOnCursor(ItemStack.empty());
        chest.setItem(4, trackedItem());
        server.getScheduler().performOneTick();
        assertEquals(1, observed.size(),
                "snapshot-or-resolve failed: unresolved="
                        + metrics.count("tracking.unique.inventory_unresolved")
                        + " incomplete="
                        + metrics.count("tracking.unique.incomplete_reconciliation")
                        + " scheduled=" + metrics.count("tracking.unique.access_scheduled"));
        assertEquals("slot:4", observed.getLast().location().containerPath());
        assertEquals(TrackingObservationUseCase.EvidenceMode.AUTHORITATIVE_TRANSITION,
                observed.getLast().mode());

        observed.clear();
        chest.clear(4);
        chest.setItem(4, trackedItem());
        listener.onClick(new InventoryClickEvent(
                player.getOpenInventory(), InventoryType.SlotType.CONTAINER,
                4, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY));
        chest.clear(4);
        player.getInventory().setItem(6, trackedItem());
        server.getScheduler().performOneTick();
        assertEquals(1, observed.size());
        assertEquals("slot:6", observed.getLast().location().containerPath());

        observed.clear();
        player.getInventory().setItem(6, null);
        player.setItemOnCursor(trackedItem());
        listener.onClick(click(ClickType.LEFT, InventoryAction.PLACE_ALL));
        server.getScheduler().performOneTick();
        assertEquals(1, observed.size());
        assertEquals("cursor", observed.getLast().location().containerPath());
    }

    @Test
    void numberKeySwapTracksIdentityFromHotbar() {
        player.openInventory(server.createInventory(null, 9));
        player.getInventory().setItem(2, trackedItem());
        InventoryClickEvent click = new InventoryClickEvent(
                player.getOpenInventory(), InventoryType.SlotType.CONTAINER,
                0, ClickType.NUMBER_KEY, InventoryAction.HOTBAR_SWAP, 2);
        listener.onClick(click);
        server.getScheduler().performOneTick();
        assertEquals(1, metrics.count("tracking.unique.access_scheduled"));
        assertEquals("slot:2", observed.getFirst().location().containerPath());
    }

    @Test
    void nestedShulkerIdentityIsReportedWithFullSlotPath() {
        player.openInventory(server.createInventory(null, 9));
        ItemStack shulker = ItemStack.of(Material.SHULKER_BOX);
        BlockStateMeta meta = (BlockStateMeta) shulker.getItemMeta();
        ShulkerBox box = (ShulkerBox) meta.getBlockState();
        box.getInventory().setItem(5, trackedItem());
        meta.setBlockState(box);
        shulker.setItemMeta(meta);
        player.setItemOnCursor(shulker);
        listener.onClick(click(ClickType.LEFT, InventoryAction.PLACE_ALL));
        player.setItemOnCursor(ItemStack.empty());
        player.getInventory().setItem(8, shulker);
        server.getScheduler().performOneTick();
        assertEquals(1, observed.size());
        assertEquals("slot:8/shulker:5", observed.getFirst().location().containerPath());
    }

    @Test
    void malformedLoreMetadataCannotUsePlainItemFastPath() {
        player.openInventory(server.createInventory(null, 9));
        ItemStack malformed = trackedItem();
        ItemMeta meta = malformed.getItemMeta();
        meta.getPersistentDataContainer().remove(
                NamespacedKey.fromString("enthusialoreitems:instance_id"));
        malformed.setItemMeta(meta);
        player.setItemOnCursor(malformed);
        listener.onClick(click(ClickType.LEFT, InventoryAction.PLACE_ALL));
        server.getScheduler().performOneTick();
        assertEquals(1, metrics.count("tracking.unique.access_scheduled"));
        assertEquals(0, metrics.count("tracking.unique.plain_click_skipped"));
    }

    @Test
    void closeReconcilesVisibleSlotWithResolvedContainer() {
        player.openInventory(server.createInventory(null, 9));
        player.getInventory().setItem(2, trackedItem());
        listener.onClose(new InventoryCloseEvent(player.getOpenInventory()));
        assertEquals(1, observed.size());
        assertEquals("slot:2", observed.getFirst().location().containerPath());
    }

    @Test
    void shutdownDrainsOneCoalescedPendingObservationOnlyOnce() {
        player.openInventory(server.createInventory(null, 9));
        player.setItemOnCursor(trackedItem());
        listener.onClick(click(ClickType.LEFT, InventoryAction.PLACE_ALL));
        listener.onClick(click(ClickType.LEFT, InventoryAction.PLACE_ALL));
        player.getInventory().setItem(5, trackedItem());
        player.setItemOnCursor(ItemStack.empty());
        listener.close();
        assertEquals(1, observed.size());
        assertEquals("slot:5", observed.getFirst().location().containerPath());
        server.getScheduler().performOneTick();
        assertEquals(1, observed.size());
    }

    private InventoryClickEvent click(ClickType type, InventoryAction action) {
        return new InventoryClickEvent(
                player.getOpenInventory(), InventoryType.SlotType.CONTAINER, 0, type, action);
    }

    private static ItemStack trackedItem() {
        return new PaperItemIdentityCodec().writeIdentity(
                ItemStack.of(Material.NETHER_STAR), IDENTITY);
    }

    private static final class CountingMetrics implements MetricsPort {
        private final Map<String, Long> counters = new HashMap<>();

        long count(String name) {
            return counters.getOrDefault(name, 0L);
        }

        @Override
        public void setGauge(String name, long value) {
        }

        @Override
        public void increment(String name) {
            counters.merge(name, 1L, Long::sum);
        }

        @Override
        public void recordDurationNanos(String name, long durationNanos) {
        }
    }
}
