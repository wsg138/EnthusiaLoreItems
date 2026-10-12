package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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

@Timeout(10)
class PaperTrackingAcknowledgementTest {
    private static final LoreItemIdentity IDENTITY = new LoreItemIdentity(
            new LoreDefinitionId(UUID.fromString("11111111-1111-1111-1111-111111111111")),
            new LoreInstanceId(UUID.fromString("22222222-2222-2222-2222-222222222222")),
            new TemplateRevision(1));

    private Plugin plugin;
    private PaperTrackingCoordinator coordinator;

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
    void asyncFailureRemainsUnresolvedUntilNewConfirmedReconciliation() {
        BlockingUseCase store = new BlockingUseCase();
        RecordingMetrics metrics = new RecordingMetrics();
        coordinator = new PaperTrackingCoordinator(plugin, () -> store, () -> 1, metrics);
        var first = request(1, TrackingObservationUseCase.EvidenceMode.RECONCILIATION);
        var ack = coordinator.submitAcknowledged(first);

        assertTrue(ack.admitted());
        assertFalse(ack.settlement().toCompletableFuture().isDone());
        store.fail(0, "database or disk is full");
        assertEquals(PaperTrackingAcknowledgement.Settlement.FAILED, status(ack));
        assertTrue(coordinator.needsReconciliation(first));
        assertEquals(1L, metrics.counter("tracking.failed"));

        var fresh = coordinator.submitAcknowledged(request(2,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION));
        store.complete(1, TrackingObservationUseCase.Status.RECORDED);
        assertEquals(PaperTrackingAcknowledgement.Settlement.CONFIRMED, status(fresh));
        assertFalse(coordinator.needsReconciliation(first));
        assertEquals(1L, metrics.counter("tracking.persistence_recovered"));
        assertEquals(1L, metrics.counter("tracking.persistence_reobservations"));
    }

    @Test
    void saturationDoesNotForgeAcknowledgementsAndCanRecoverOnFreshSubmit() {
        BlockingUseCase store = new BlockingUseCase();
        coordinator = new PaperTrackingCoordinator(plugin, () -> store, () -> 1,
                MetricsPort.noOp());
        for (int index = 0; index < 9; index++) {
            assertTrue(coordinator.submit(request(index,
                    TrackingObservationUseCase.EvidenceMode.RECONCILIATION)));
        }
        var refused = coordinator.submitAcknowledged(request(99,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION));
        assertFalse(refused.admitted());
        assertEquals(PaperTrackingAcknowledgement.Settlement.REJECTED, status(refused));
        assertTrue(coordinator.needsReconciliation(request(99,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION)));
        for (int index = 0; index < 9; index++) {
            awaitStages(store, index + 1);
            store.complete(index, TrackingObservationUseCase.Status.RECORDED);
        }

        var recovered = coordinator.submitAcknowledged(request(99,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION));
        store.complete(9, TrackingObservationUseCase.Status.RECORDED);
        assertEquals(PaperTrackingAcknowledgement.Settlement.CONFIRMED, status(recovered));
        assertFalse(coordinator.needsReconciliation(request(99,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION)));
    }

    @Test
    void schedulerRejectionAndSynchronousThrowAreFailuresNotCommits() {
        AtomicInteger calls = new AtomicInteger();
        TrackingObservationUseCase rejecting = request -> {
            if (calls.getAndIncrement() == 0) {
                throw new RejectedExecutionException("database executor saturated");
            }
            return CompletableFuture.completedFuture(
                    result(TrackingObservationUseCase.Status.UNCHANGED));
        };
        coordinator = new PaperTrackingCoordinator(plugin, () -> rejecting, () -> 1,
                MetricsPort.noOp());
        var request = request(3, TrackingObservationUseCase.EvidenceMode.RECONCILIATION);

        var rejected = coordinator.submitAcknowledged(request);
        assertTrue(rejected.admitted());
        assertEquals(PaperTrackingAcknowledgement.Settlement.FAILED, status(rejected));
        assertTrue(coordinator.needsReconciliation(request));

        var next = coordinator.submitAcknowledged(request);
        assertEquals(PaperTrackingAcknowledgement.Settlement.CONFIRMED, status(next));
        assertFalse(coordinator.needsReconciliation(request));
    }

    @Test
    void laterFailedMoveCannotBeClearedByAlreadySettledNewerMove() {
        BlockingUseCase store = new BlockingUseCase();
        coordinator = new PaperTrackingCoordinator(plugin, () -> store, () -> 2,
                MetricsPort.noOp());
        var older = coordinator.submitAcknowledged(request(1,
                TrackingObservationUseCase.EvidenceMode.AUTHORITATIVE_TRANSITION));
        var newer = coordinator.submitAcknowledged(request(2,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION));

        store.complete(1, TrackingObservationUseCase.Status.RECORDED);
        assertEquals(PaperTrackingAcknowledgement.Settlement.CONFIRMED, status(newer));
        store.fail(0, "SQLITE_BUSY: write transaction rolled back");
        assertEquals(PaperTrackingAcknowledgement.Settlement.FAILED, status(older));
        assertTrue(coordinator.needsReconciliation(request(2,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION)));

        var fresh = coordinator.submitAcknowledged(request(3,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION));
        store.complete(2, TrackingObservationUseCase.Status.RECORDED);
        assertEquals(PaperTrackingAcknowledgement.Settlement.CONFIRMED, status(fresh));
        assertFalse(coordinator.needsReconciliation(request(2,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION)));
    }

    @Test
    void coalescedNaturalEventIsUnverifiedAndFailureReopensAdmission() {
        BlockingUseCase store = new BlockingUseCase();
        coordinator = new PaperTrackingCoordinator(plugin, () -> store, () -> 1,
                MetricsPort.noOp());
        var request = naturalRequest();
        var first = coordinator.submitAcknowledged(request);
        var duplicate = coordinator.submitAcknowledged(request);

        assertTrue(duplicate.admitted());
        assertEquals(PaperTrackingAcknowledgement.Settlement.COALESCED_UNVERIFIED,
                status(duplicate));
        assertFalse(first.settlement().toCompletableFuture().isDone());
        store.fail(0, "SQLITE_BUSY");
        assertTrue(coordinator.needsReconciliation(request));

        var fresh = coordinator.submitAcknowledged(request);
        assertTrue(fresh.admitted());
        assertEquals(2, store.stages.size());
        store.complete(1, TrackingObservationUseCase.Status.UNCHANGED);
        assertEquals(PaperTrackingAcknowledgement.Settlement.CONFIRMED, status(fresh));
    }

    @Test
    void nonCommittingAndConflictResultsNeverPretendToBeUniqueCommit() {
        BlockingUseCase store = new BlockingUseCase();
        coordinator = new PaperTrackingCoordinator(plugin, () -> store, () -> 1,
                MetricsPort.noOp());
        var stale = coordinator.submitAcknowledged(request(1,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION));
        store.complete(0, TrackingObservationUseCase.Status.STALE);
        assertEquals(PaperTrackingAcknowledgement.Settlement.NOT_RECORDED, status(stale));
        var conflict = coordinator.submitAcknowledged(request(2,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION));
        store.complete(1, TrackingObservationUseCase.Status.CONFLICT_RECORDED);
        assertEquals(PaperTrackingAcknowledgement.Settlement.CONFLICT_RECORDED,
                status(conflict));
    }

    @Test
    void shutdownDrainsInflightEvenIfStoreFailsAndLeavesUncertaintyVisible() {
        BlockingUseCase store = new BlockingUseCase();
        coordinator = new PaperTrackingCoordinator(plugin, () -> store, () -> 1,
                MetricsPort.noOp());
        var outstanding = coordinator.submitAcknowledged(request(4,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION));
        var drained = PaperTrackingCoordinator.quiescenceFor(plugin).toCompletableFuture();
        coordinator.close();
        assertFalse(drained.isDone());

        store.fail(0, "WAL I/O failure");
        drained.join();
        assertEquals(PaperTrackingAcknowledgement.Settlement.FAILED, status(outstanding));
        assertEquals(1, coordinator.durabilitySnapshot().unresolvedHolders());
        assertFalse(coordinator.submitAcknowledged(request(5,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION)).admitted());
    }

    @Test
    void supplierFailureAndNullStageAreNotDurableAcknowledgements() {
        coordinator = new PaperTrackingCoordinator(plugin,
                () -> { throw new IllegalStateException("store not initialized"); },
                () -> 1, MetricsPort.noOp());
        var failed = coordinator.submitAcknowledged(request(1,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION));
        assertFalse(failed.admitted());
        assertEquals(PaperTrackingAcknowledgement.Settlement.FAILED, status(failed));
        assertEquals(1, coordinator.durabilitySnapshot().unresolvedHolders());
    }

    private static PaperTrackingAcknowledgement.Settlement status(
            PaperTrackingAcknowledgement acknowledgement) {
        return acknowledgement.settlement().toCompletableFuture().join();
    }

    private static void awaitStages(BlockingUseCase useCase, int expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (useCase.stages.size() < expected && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(expected, useCase.stages.size());
    }

    private static TrackingObservationUseCase.Request naturalRequest() {
        return new TrackingObservationUseCase.Request(IDENTITY,
                new LocationDescriptor(LocationDescriptor.Type.PLAYER_INVENTORY,
                        "player:33333333-3333-3333-3333-333333333333", "slot:1"),
                TrackingObservationUseCase.Presence.PRESENT,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                "paper-natural-unique");
    }

    private static TrackingObservationUseCase.Request request(
            int slot, TrackingObservationUseCase.EvidenceMode mode) {
        return new TrackingObservationUseCase.Request(IDENTITY,
                new LocationDescriptor(LocationDescriptor.Type.PLAYER_INVENTORY,
                        "player:33333333-3333-3333-3333-333333333333", "slot:" + slot),
                TrackingObservationUseCase.Presence.PRESENT, mode, "durable-ack-test");
    }

    private static TrackingObservationUseCase.Result result(
            TrackingObservationUseCase.Status status) {
        return TrackingObservationUseCase.Result.of(status, "settled");
    }

    private static final class BlockingUseCase implements TrackingObservationUseCase {
        private final List<CompletableFuture<Result>> stages = new CopyOnWriteArrayList<>();

        @Override
        public CompletableFuture<Result> record(Request request) {
            CompletableFuture<Result> stage = new CompletableFuture<>();
            stages.add(stage);
            return stage;
        }

        private void complete(int index, Status status) {
            stages.get(index).complete(result(status));
        }

        private void fail(int index, String message) {
            stages.get(index).completeExceptionally(
                    new CompletionException(new IllegalStateException(message)));
        }
    }

    private static final class RecordingMetrics implements MetricsPort {
        private final ConcurrentHashMap<String, AtomicLong> counters =
                new ConcurrentHashMap<>();

        @Override
        public void setGauge(String name, long value) {}

        @Override
        public void increment(String name) {
            counters.computeIfAbsent(name, ignored -> new AtomicLong()).incrementAndGet();
        }

        @Override
        public void recordDurationNanos(String name, long value) {}

        private long counter(String name) {
            return counters.getOrDefault(name, new AtomicLong()).get();
        }
    }
}
