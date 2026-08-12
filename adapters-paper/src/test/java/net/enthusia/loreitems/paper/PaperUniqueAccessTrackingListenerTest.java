package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import net.enthusia.loreitems.domain.LocationDescriptor;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

class PaperUniqueAccessTrackingListenerTest {
    private static final String PLAYER_NAME = "alice";
    private static final LoreItemIdentity IDENTITY = new LoreItemIdentity(
            new LoreDefinitionId(UUID.fromString(
                    "11111111-1111-1111-1111-111111111111")),
            new LoreInstanceId(UUID.fromString(
                    "22222222-2222-2222-2222-222222222222")),
            new TemplateRevision(1));

    private ServerMock server;
    private PluginMock plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void quitObservationsAreSubmittedSynchronouslyAsLastConfirmed() {
        AtomicReference<TrackingObservationUseCase.Request> observed = new AtomicReference<>();
        TrackingObservationUseCase useCase = request -> {
            observed.set(request);
            return CompletableFuture.completedFuture(TrackingObservationUseCase.Result.of(
                    TrackingObservationUseCase.Status.RECORDED,
                    "ok"));
        };
        PaperUniqueAccessTrackingListener listener = listener(useCase);
        listener.start();
        PlayerMock player = server.addPlayer(PLAYER_NAME);
        player.getInventory().setItem(0, trackedItem());

        listener.onQuit(new PlayerQuitEvent(
                player, (Component) null, PlayerQuitEvent.QuitReason.DISCONNECTED));

        TrackingObservationUseCase.Request request = observed.get();
        assertEquals(TrackingObservationUseCase.Presence.LAST_CONFIRMED, request.presence());
        assertEquals("player-quit-unique", request.source());
        listener.close();
    }

    @Test
    void trailingInventoryCloseAfterQuitDoesNotRestoreLiveConfirmation() {
        List<TrackingObservationUseCase.Request> observed = new CopyOnWriteArrayList<>();
        PaperUniqueAccessTrackingListener listener = listener(recordingUseCase(observed));
        listener.start();
        PlayerMock player = server.addPlayer(PLAYER_NAME);
        player.getInventory().setItem(0, trackedItem());

        listener.onQuit(new PlayerQuitEvent(
                player, (Component) null, PlayerQuitEvent.QuitReason.DISCONNECTED));
        listener.onClose(new InventoryCloseEvent(player.getOpenInventory()));

        assertEquals(1, observed.size());
        assertEquals(TrackingObservationUseCase.Presence.LAST_CONFIRMED, observed.get(0).presence());
        assertEquals("player-quit-unique", observed.get(0).source());
        listener.close();
    }

    @Test
    void duplicatePlayerCopiesAreSubmittedAsReconciliationEvidence() {
        AtomicReference<TrackingObservationUseCase.Request> observed = new AtomicReference<>();
        TrackingObservationUseCase useCase = request -> {
            observed.set(request);
            return CompletableFuture.completedFuture(TrackingObservationUseCase.Result.of(
                    TrackingObservationUseCase.Status.RECORDED,
                    "ok"));
        };
        PaperUniqueAccessTrackingListener listener = listener(useCase);
        PlayerMock player = server.addPlayer(PLAYER_NAME);
        ItemStack tracked = trackedItem();
        player.getInventory().setItem(0, tracked);
        player.getInventory().setItem(1, tracked.clone());

        listener.submitPlayer(player, false, "test-duplicate");

        assertEquals(
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                observed.get().mode());
        listener.close();
    }

    @Test
    void playerAndContainerCopiesShareOneUniquenessDecision() {
        List<TrackingObservationUseCase.Request> observed = new CopyOnWriteArrayList<>();
        TrackingObservationUseCase useCase = recordingUseCase(observed);
        PaperUniqueAccessTrackingListener listener = listener(useCase);
        PlayerMock player = server.addPlayer(PLAYER_NAME);
        ItemStack tracked = trackedItem();
        player.getInventory().setItem(0, tracked);
        Inventory container = server.createInventory(null, 9);
        container.setItem(0, tracked.clone());

        listener.submitPlayerAndInventory(
                player,
                container,
                LocationDescriptor.Type.BLOCK_CONTAINER,
                "minecraft:overworld:0:64:0",
                "test-cross-inventory-duplicate");

        assertReconciliation(observed);
        listener.close();
    }

    @Test
    void playerAndDisplayCopiesShareOneUniquenessDecision() {
        List<TrackingObservationUseCase.Request> observed = new CopyOnWriteArrayList<>();
        TrackingObservationUseCase useCase = recordingUseCase(observed);
        PaperUniqueAccessTrackingListener listener = listener(useCase);
        PlayerMock player = server.addPlayer(PLAYER_NAME);
        ItemStack tracked = trackedItem();
        player.getInventory().setItem(0, tracked);
        LocationDescriptor display = new LocationDescriptor(
                LocationDescriptor.Type.ITEM_FRAME,
                "minecraft:overworld:4:64:7:33333333-3333-3333-3333-333333333333",
                "item");

        listener.submitPlayerAndDisplay(
                player,
                tracked.clone(),
                display,
                "test-display-duplicate");

        assertReconciliation(observed);
        listener.close();
    }

    private PaperUniqueAccessTrackingListener listener(TrackingObservationUseCase useCase) {
        return new PaperUniqueAccessTrackingListener(
                plugin, () -> useCase, () -> 4, MetricsPort.noOp());
    }

    private static TrackingObservationUseCase recordingUseCase(
            List<TrackingObservationUseCase.Request> observed) {
        return request -> {
            observed.add(request);
            return CompletableFuture.completedFuture(TrackingObservationUseCase.Result.of(
                    TrackingObservationUseCase.Status.RECORDED,
                    "ok"));
        };
    }

    private static void assertReconciliation(
            List<TrackingObservationUseCase.Request> observed) {
        assertEquals(2, observed.size());
        assertTrue(observed.stream().allMatch(request -> request.mode()
                == TrackingObservationUseCase.EvidenceMode.RECONCILIATION));
    }

    private static ItemStack trackedItem() {
        return new PaperItemIdentityCodec().writeIdentity(
                ItemStack.of(Material.NETHER_STAR), IDENTITY);
    }
}
