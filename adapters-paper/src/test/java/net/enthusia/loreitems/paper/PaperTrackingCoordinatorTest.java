package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import net.enthusia.loreitems.domain.LocationDescriptor;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockbukkit.mockbukkit.MockBukkit;

class PaperTrackingCoordinatorTest {
    private static final LoreItemIdentity IDENTITY = new LoreItemIdentity(
            new LoreDefinitionId(UUID.fromString(
                    "11111111-1111-1111-1111-111111111111")),
            new LoreInstanceId(UUID.fromString(
                    "22222222-2222-2222-2222-222222222222")),
            new TemplateRevision(1));

    private PaperTrackingCoordinator coordinator;
    private Plugin plugin;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
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
    void invalidReloadedBudgetCannotStallAQueuedCompletion() {
        AtomicInteger budget = new AtomicInteger(1);
        BlockingUseCase useCase = new BlockingUseCase();
        coordinator = new PaperTrackingCoordinator(
                plugin, () -> useCase, budget::get, MetricsPort.noOp());

        assertTrue(coordinator.submit(request(1)));
        assertTrue(coordinator.submit(request(2)));
        assertEquals(1, useCase.requests.size());

        budget.set(0);
        useCase.complete(0);

        assertEquals(2, useCase.requests.size());
        useCase.completeAll();
    }

    @Test
    @Timeout(5)
    void closeDrainsBacklogWithoutExceedingConfiguredInFlightBound() {
        BlockingUseCase useCase = new BlockingUseCase();
        coordinator = new PaperTrackingCoordinator(
                plugin, () -> useCase, () -> 1, MetricsPort.noOp());

        assertTrue(coordinator.submit(request(1)));
        assertTrue(coordinator.submit(request(2)));
        assertTrue(coordinator.submit(request(3)));
        assertEquals(1, useCase.requests.size());

        CompletableFuture<Void> closing = CompletableFuture.runAsync(coordinator::close);
        assertFalse(closing.isDone());
        assertEquals(1, useCase.requests.size());

        useCase.complete(0);
        awaitRequestCount(useCase, 2);
        assertFalse(closing.isDone());

        useCase.complete(1);
        awaitRequestCount(useCase, 3);
        assertFalse(closing.isDone());

        useCase.complete(2);
        closing.join();
        assertEquals(3, useCase.requests.size());
    }

    private static void awaitRequestCount(BlockingUseCase useCase, int expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (useCase.requests.size() < expected && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(expected, useCase.requests.size());
    }

    private static TrackingObservationUseCase.Request request(int slot) {
        return new TrackingObservationUseCase.Request(
                IDENTITY,
                new LocationDescriptor(
                        LocationDescriptor.Type.PLAYER_INVENTORY,
                        "player:33333333-3333-3333-3333-333333333333",
                        "slot:" + slot),
                TrackingObservationUseCase.Presence.PRESENT,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                "coordinator-test");
    }

    private static TrackingObservationUseCase.Result recorded() {
        return TrackingObservationUseCase.Result.of(
                TrackingObservationUseCase.Status.RECORDED,
                "Recorded.");
    }

    private static final class BlockingUseCase implements TrackingObservationUseCase {
        private final List<Request> requests = new CopyOnWriteArrayList<>();
        private final List<CompletableFuture<Result>> stages = new CopyOnWriteArrayList<>();

        @Override
        public CompletionStage<Result> record(Request request) {
            requests.add(request);
            CompletableFuture<Result> stage = new CompletableFuture<>();
            stages.add(stage);
            return stage;
        }

        private void complete(int index) {
            stages.get(index).complete(recorded());
        }

        private void completeAll() {
            stages.forEach(stage -> stage.complete(recorded()));
        }
    }
}
