package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class PaperUniqueAccessTrackingListenerTest {
    private static final LoreItemIdentity IDENTITY = new LoreItemIdentity(
            new LoreDefinitionId(UUID.fromString(
                    "11111111-1111-1111-1111-111111111111")),
            new LoreInstanceId(UUID.fromString(
                    "22222222-2222-2222-2222-222222222222")),
            new TemplateRevision(1));

    private PaperUniqueAccessTrackingListener listener;
    private PlayerMock player;
    private RecordingUseCase useCase;

    @BeforeEach
    void setUp() {
        ServerMock server = MockBukkit.mock();
        Plugin plugin = MockBukkit.createMockPlugin();
        player = server.addPlayer();
        useCase = new RecordingUseCase();
        listener = new PaperUniqueAccessTrackingListener(
                plugin, () -> useCase, () -> 16, MetricsPort.noOp());
    }

    @AfterEach
    void tearDown() {
        listener.close();
        MockBukkit.unmock();
    }

    @Test
    void quitRecordsOnlyLastConfirmedReconciliationForEachObservedLocation()
            throws ReflectiveOperationException {
        ItemStack tracked = new PaperItemIdentityCodec().writeIdentity(
                ItemStack.of(Material.DIAMOND_SWORD), IDENTITY);
        player.getInventory().setItem(0, tracked);

        Method submitPlayer = PaperUniqueAccessTrackingListener.class.getDeclaredMethod(
                "submitPlayer", Player.class, boolean.class, String.class);
        submitPlayer.setAccessible(true);
        submitPlayer.invoke(listener, player, true, "player-quit-unique");

        assertEquals(1, useCase.requests.size());
        TrackingObservationUseCase.Request request = useCase.requests.getFirst();
        assertEquals(TrackingObservationUseCase.Presence.LAST_CONFIRMED, request.presence());
        assertEquals(
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                request.mode());
    }

    private static final class RecordingUseCase implements TrackingObservationUseCase {
        private final List<Request> requests = new ArrayList<>();

        @Override
        public CompletionStage<Result> record(Request request) {
            requests.add(request);
            return CompletableFuture.completedFuture(Result.of(
                    Status.RECORDED,
                    "Recorded."));
        }
    }
}
