package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.bukkit.Material;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

class PaperUniqueAccessTrackingListenerTest {
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
        PaperUniqueAccessTrackingListener listener = new PaperUniqueAccessTrackingListener(
                plugin,
                () -> useCase,
                () -> 4,
                MetricsPort.noOp());
        listener.start();
        PlayerMock player = server.addPlayer("alice");
        player.getInventory().setItem(0, trackedItem());

        listener.onQuit(new PlayerQuitEvent(
                player, (String) null, PlayerQuitEvent.QuitReason.DISCONNECTED));

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
                MetricsPort.noOp());
        PlayerMock player = server.addPlayer("alice");
        ItemStack tracked = trackedItem();
        player.getInventory().setItem(0, tracked);
        player.getInventory().setItem(1, tracked.clone());

        listener.submitPlayer(player, false, "test-duplicate");

        TrackingObservationUseCase.Request request = observed.get();
        assertEquals(
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                request.mode());
        listener.close();
    }

    private static ItemStack trackedItem() {
        return new PaperItemIdentityCodec().writeIdentity(
                ItemStack.of(Material.NETHER_STAR), IDENTITY);
    }
}
