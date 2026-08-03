package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.ItemAnomalyObservationUseCase;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class PaperIdentityAnomalyListenerTest {
    private static final LoreItemIdentity IDENTITY = new LoreItemIdentity(
            new LoreDefinitionId(UUID.fromString(
                    "11111111-1111-1111-1111-111111111111")),
            new LoreInstanceId(UUID.fromString(
                    "22222222-2222-2222-2222-222222222222")),
            new TemplateRevision(1));

    private ServerMock server;
    private PlayerMock player;
    private RecordingUseCase useCase;
    private PaperIdentityAnomalyListener listener;
    private PaperItemIdentityCodec identityCodec;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer();
        Plugin plugin = MockBukkit.createMockPlugin();
        useCase = new RecordingUseCase();
        server.getServicesManager().register(
                ItemAnomalyObservationUseCase.class,
                useCase,
                plugin,
                ServicePriority.Normal);
        listener = new PaperIdentityAnomalyListener(plugin, 8);
        identityCodec = new PaperItemIdentityCodec();
    }

    @AfterEach
    void tearDown() {
        listener.close();
        MockBukkit.unmock();
    }

    @Test
    void startupScanRecordsDuplicateInventoryCopiesWithoutDeletingEither() {
        ItemStack first = trackedItem();
        ItemStack second = trackedItem();
        player.getInventory().setItem(0, first);
        player.getInventory().setItem(1, second);

        listener.start();

        assertEquals(1, useCase.requests.size());
        ItemAnomalyObservationUseCase.Request request = useCase.requests.getFirst();
        assertEquals(ItemAnomalyObservationUseCase.Kind.DUPLICATE_INSTANCE, request.kind());
        assertEquals(2, request.evidenceLocations().size());
        assertEquals(first, player.getInventory().getItem(0));
        assertEquals(second, player.getInventory().getItem(1));
    }

    @Test
    void startupScanRecordsRecoverableMalformedStackEvidence() {
        ItemStack malformed = trackedItem();
        malformed.setAmount(2);
        player.getInventory().setItem(0, malformed);

        listener.start();

        assertEquals(1, useCase.requests.size());
        ItemAnomalyObservationUseCase.Request request = useCase.requests.getFirst();
        assertEquals(ItemAnomalyObservationUseCase.Kind.MALFORMED_STACK, request.kind());
        assertTrue(request.detail().contains("STACKING_VIOLATION"));
        assertEquals(2, player.getInventory().getItem(0).getAmount());
    }

    private ItemStack trackedItem() {
        return identityCodec.writeIdentity(ItemStack.of(Material.DIAMOND), IDENTITY);
    }

    private static final class RecordingUseCase implements ItemAnomalyObservationUseCase {
        private final List<Request> requests = new ArrayList<>();

        @Override
        public CompletionStage<Result> record(Request request) {
            requests.add(request);
            return CompletableFuture.completedFuture(Result.of(
                    Status.RECORDED,
                    "recorded"));
        }
    }
}
