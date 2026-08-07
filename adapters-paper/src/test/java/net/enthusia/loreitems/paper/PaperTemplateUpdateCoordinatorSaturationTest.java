package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.IntStream;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.PreparedTemplateUpdate;
import net.enthusia.loreitems.application.TemplateUpdateExecutionUseCase;
import net.enthusia.loreitems.application.TemplateUpdatePrepareResult;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class PaperTemplateUpdateCoordinatorSaturationTest {
    private static final LoreDefinitionId DEFINITION_ID = new LoreDefinitionId(
            UUID.fromString("11111111-1111-1111-1111-111111111111"));

    private PlayerMock player;
    private Plugin plugin;
    private PaperTemplateUpdateCoordinator coordinator;

    @BeforeEach
    void setUp() {
        ServerMock server = MockBukkit.mock();
        player = server.addPlayer();
        plugin = MockBukkit.createMockPlugin();
    }

    @AfterEach
    void tearDown() {
        if (coordinator != null) {
            coordinator.close();
        }
        MockBukkit.unmock();
    }

    @Test
    void oneInFlightPlusEightQueuedIsTheExactCapacityForBudgetOne() {
        BlockingUseCase useCase = new BlockingUseCase();
        coordinator = new PaperTemplateUpdateCoordinator(
                plugin, useCase, new PaperTemplateUpdateOperator(), 1);
        List<PaperTemplateUpdateScanner.Candidate> candidates = candidates(10);

        candidates.subList(0, 9).forEach(candidate -> assertTrue(coordinator.submit(candidate)));
        assertFalse(coordinator.submit(candidates.get(9)));
        assertEquals(1, useCase.preparedIdentities.size());

        assertTrue(coordinator.submit(candidates.get(0)));
        assertEquals(1, useCase.preparedIdentities.size());
    }

    private List<PaperTemplateUpdateScanner.Candidate> candidates(int count) {
        PaperInventoryReference inventory =
                new PaperInventoryReference.PlayerMain(player.getUniqueId());
        return IntStream.range(0, count)
                .mapToObj(index -> candidate(inventory, index))
                .toList();
    }

    private static PaperTemplateUpdateScanner.Candidate candidate(
            PaperInventoryReference inventory, int index) {
        LoreItemIdentity identity = new LoreItemIdentity(
                DEFINITION_ID,
                new LoreInstanceId(new UUID(0L, index + 1L)),
                new TemplateRevision(1L));
        return new PaperTemplateUpdateScanner.Candidate(
                identity,
                PaperTemplateUpdateItemReference.root(inventory, 0));
    }

    private static final class BlockingUseCase implements TemplateUpdateExecutionUseCase {
        private final List<LoreItemIdentity> preparedIdentities = new ArrayList<>();

        @Override
        public CompletionStage<TemplateUpdatePrepareResult> prepare(LoreItemIdentity observedIdentity) {
            preparedIdentities.add(observedIdentity);
            return new CompletableFuture<>();
        }

        @Override
        public CompletionStage<Boolean> release(PreparedTemplateUpdate update, String reason) {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletionStage<Boolean> complete(
                PreparedTemplateUpdate update,
                String beforeFingerprint,
                String afterFingerprint) {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletionStage<Boolean> requireReview(
                PreparedTemplateUpdate update,
                String reason,
                String beforeFingerprint,
                String afterFingerprint) {
            return CompletableFuture.completedFuture(true);
        }
    }
}
