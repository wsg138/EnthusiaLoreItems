package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

class PaperUniqueAccessTrackingListenerTest {
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
        PaperUniqueAccessTrackingListener listener = new PaperUniqueAccessTrackingListener(
                plugin,
                () -> useCase,
                () -> 4,
                new MetricsPort() {
                    @Override
                    public void setGauge(String name, long value) {}

                    @Override
                    public void increment(String name) {}

                    @Override
                    public void recordDurationNanos(String name, long value) {}
                });
        listener.start();
        PlayerMock player = server.addPlayer("alice");
        player.getInventory().setItem(0, trackedItem(player));

        listener.onQuit(new org.bukkit.event.player.PlayerQuitEvent(
                player, org.bukkit.event.player.PlayerQuitEvent.QuitReason.QUIT, null));

        TrackingObservationUseCase.Request request = observed.get();
        assertEquals(TrackingObservationUseCase.Presence.LAST_CONFIRMED, request.presence());
        assertEquals("player-quit-unique", request.source());
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
        PaperUniqueAccessTrackingListener listener = new PaperUniqueAccessTrackingListener(
                plugin,
                () -> useCase,
                () -> 4,
                new MetricsPort() {
                    @Override
                    public void setGauge(String name, long value) {}

                    @Override
                    public void increment(String name) {}

                    @Override
                    public void recordDurationNanos(String name, long value) {}
                });
        PlayerMock player = server.addPlayer("alice");
        ItemStack tracked = trackedItem(player);
        player.getInventory().setItem(0, tracked);
        player.getInventory().setItem(1, tracked.clone());

        listener.submitPlayer(player, false, "test-duplicate");

        TrackingObservationUseCase.Request request = observed.get();
        assertEquals(
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                request.mode());
        listener.close();
    }

    private ItemStack trackedItem(Player player) {
        ItemStack item = new ItemStack(Material.NETHER_STAR);
        var meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(
                PaperItemIdentityCodec.DEFINITION_ID_KEY,
                PersistentDataType.STRING,
                java.util.UUID.randomUUID().toString());
        meta.getPersistentDataContainer().set(
                PaperItemIdentityCodec.INSTANCE_ID_KEY,
                PersistentDataType.STRING,
                java.util.UUID.randomUUID().toString());
        meta.getPersistentDataContainer().set(
                PaperItemIdentityCodec.APPLIED_REVISION_KEY,
                PersistentDataType.LONG,
                1L);
        item.setItemMeta(meta);
        assertInstanceOf(Player.class, player);
        return item;
    }
}
