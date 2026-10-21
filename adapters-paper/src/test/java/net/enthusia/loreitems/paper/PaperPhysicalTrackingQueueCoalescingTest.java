package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.enthusia.loreitems.application.TrackingMetrics;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

/** Prevents routine queue optimizations from silently removing lifecycle evidence. */
class PaperPhysicalTrackingQueueCoalescingTest {
    private static final String PERIODIC_SOURCE = "periodic-loaded-chunk";
    private ServerMock server;
    private PaperPhysicalTrackingListener listener;
    private TrackingMetrics metrics;
    private AtomicInteger scanBudget;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        PluginMock plugin = MockBukkit.createMockPlugin();
        TrackingObservationUseCase recorder = request ->
                CompletableFuture.completedFuture(TrackingObservationUseCase.Result.of(
                        TrackingObservationUseCase.Status.RECORDED, "ok"));
        metrics = new TrackingMetrics();
        scanBudget = new AtomicInteger(4);
        listener = new PaperPhysicalTrackingListener(
                plugin, () -> recorder, scanBudget::get, metrics);
    }

    @AfterEach
    void tearDown() {
        listener.close();
        MockBukkit.unmock();
    }

    @Test
    void repeatedPeriodicRequestsShareOneQueueSlotButChunkLoadDoesNot() {
        World world = server.addSimpleWorld("world");
        Chunk chunk = world.getChunkAt(0, 0);
        PaperTrackingScanRequest periodic = PaperTrackingScanRequest.chunk(
                chunk, TrackingObservationUseCase.Presence.PRESENT,
                PERIODIC_SOURCE);

        for (int n = 0; n < 50; n++) {
            listener.enqueue(periodic);
        }

        assertEquals(1, listener.queuedScanCount());
        assertEquals(1, listener.queuedPeriodicChunkCount());
        listener.enqueue(PaperTrackingScanRequest.chunk(
                chunk, TrackingObservationUseCase.Presence.PRESENT,
                PERIODIC_SOURCE));
        assertEquals(1, listener.queuedScanCount());

        // A real chunk-load observation must not be discarded merely because a
        // background periodic reconciliation for the same chunk is pending.
        listener.enqueue(PaperTrackingScanRequest.chunk(
                chunk, TrackingObservationUseCase.Presence.PRESENT, "chunk-load"));
        listener.enqueue(PaperTrackingScanRequest.chunk(
                chunk, TrackingObservationUseCase.Presence.PRESENT, "chunk-load"));
        assertEquals(3, listener.queuedScanCount());
        assertEquals(1, listener.queuedPeriodicChunkCount());

        // Even if a caller accidentally uses the periodic label for a
        // LAST_CONFIRMED observation, it must never be coalesced into PRESENT.
        listener.enqueue(PaperTrackingScanRequest.chunk(
                chunk, TrackingObservationUseCase.Presence.LAST_CONFIRMED,
                PERIODIC_SOURCE));
        assertEquals(4, listener.queuedScanCount());
        assertEquals(1, listener.queuedPeriodicChunkCount());
    }

    @Test
    void sameCoordinatesInDifferentWorldsNeverCoalesce() {
        World first = server.addSimpleWorld("one");
        World second = server.addSimpleWorld("two");
        listener.enqueue(PaperTrackingScanRequest.chunk(
                first.getChunkAt(0, 0), TrackingObservationUseCase.Presence.PRESENT,
                PERIODIC_SOURCE));
        listener.enqueue(PaperTrackingScanRequest.chunk(
                second.getChunkAt(0, 0), TrackingObservationUseCase.Presence.PRESENT,
                PERIODIC_SOURCE));

        assertEquals(2, listener.queuedScanCount());
        assertEquals(2, listener.queuedPeriodicChunkCount());
    }

    @Test
    void recordsActualPeakAndDistinguishesEventFromRoutineRejections() {
        World world = server.addSimpleWorld("world");
        for (int i = 0; i < 127; i++) {
            listener.enqueue(PaperTrackingScanRequest.player(
                    new UUID(0L, i + 1L), true, "player-join"));
        }
        listener.enqueue(PaperTrackingScanRequest.chunk(
                world.getChunkAt(0, 0),
                TrackingObservationUseCase.Presence.PRESENT, PERIODIC_SOURCE));
        assertEquals(128, listener.queuedScanCount());
        assertEquals(128L, metrics.snapshot().scanBacklogPeak());
        assertEquals(127L, metrics.snapshot().scanEventBacklog());
        assertEquals(1L, metrics.snapshot().scanPeriodicBacklog());

        listener.enqueue(PaperTrackingScanRequest.player(
                UUID.randomUUID(), true, "player-join"));
        listener.enqueue(PaperTrackingScanRequest.chunk(
                world.getChunkAt(1, 0),
                TrackingObservationUseCase.Presence.PRESENT, PERIODIC_SOURCE));
        assertEquals(0L, metrics.snapshot().scanEventRejected(),
                "New lifecycle work displaces routine reconciliation, never event scans");
        assertEquals(2L, metrics.snapshot().scanPeriodicRejected());
        assertEquals(0L, metrics.snapshot().scanPlayerRejected());
        assertEquals(0L, metrics.snapshot().scanChunkRejected());
        assertEquals(0, listener.queuedPeriodicChunkCount());
        assertEquals(128, listener.queuedScanCount());
        assertEquals(128L, metrics.snapshot().scanBacklogPeak());
    }

    @Test
    void allEventBacklogStillHasBoundedSafeRejection() {
        for (int i = 0; i < 128; i++) {
            listener.enqueue(PaperTrackingScanRequest.player(
                    new UUID(0L, i + 1L), true, "player-join"));
        }
        listener.enqueue(PaperTrackingScanRequest.player(
                UUID.randomUUID(), true, "player-join"));

        assertEquals(128, listener.queuedScanCount());
        assertEquals(1L, metrics.snapshot().scanEventRejected());
        assertEquals(0L, metrics.snapshot().scanPeriodicRejected());
        assertEquals(1L, metrics.snapshot().scanPlayerRejected());
        assertEquals(0L, metrics.snapshot().scanChunkRejected());
    }

    @Test
    void recordsWhichLifecycleSourceExhaustedTheEventQueue() {
        World world = server.addSimpleWorld("world");
        for (int i = 0; i < 128; i++) {
            listener.enqueue(PaperTrackingScanRequest.player(
                    new UUID(0L, i + 1L), true, "tracking-start"));
        }
        listener.enqueue(PaperTrackingScanRequest.chunk(
                world.getChunkAt(5, 0),
                TrackingObservationUseCase.Presence.PRESENT, "chunk-load"));

        assertEquals(128, listener.queuedScanCount());
        assertEquals(1L, metrics.snapshot().scanEventRejected());
        assertEquals(0L, metrics.snapshot().scanPlayerRejected());
        assertEquals(1L, metrics.snapshot().scanChunkRejected());
        assertEquals(0L, metrics.snapshot().scanPeriodicRejected());
    }

    @Test
    void runtimeBudgetReductionCannotExpandAnAlreadyOversizedBacklog() {
        World world = server.addSimpleWorld("world");
        for (int i = 0; i < 127; i++) {
            listener.enqueue(PaperTrackingScanRequest.player(
                    new UUID(0L, i + 1L), true, "player-join"));
        }
        listener.enqueue(PaperTrackingScanRequest.chunk(
                world.getChunkAt(0, 0),
                TrackingObservationUseCase.Presence.PRESENT, PERIODIC_SOURCE));

        scanBudget.set(2); // Capacity drops from 128 to 64 without deleting queued evidence.
        listener.enqueue(PaperTrackingScanRequest.player(
                UUID.randomUUID(), true, "chunk-load"));

        assertEquals(128, listener.queuedScanCount());
        assertEquals(1, listener.queuedPeriodicChunkCount());
        assertEquals(1L, metrics.snapshot().scanEventRejected());
        assertEquals(0L, metrics.snapshot().scanPeriodicRejected());
    }

    @Test
    void routineReconciliationMakesProgressDuringEventFlood() {
        World world = server.addSimpleWorld("world");
        listener.enqueue(PaperTrackingScanRequest.chunk(
                world.getChunkAt(0, 0),
                TrackingObservationUseCase.Presence.PRESENT, PERIODIC_SOURCE));
        for (int i = 0; i < 12; i++) {
            listener.enqueue(PaperTrackingScanRequest.player(
                    new UUID(0L, i + 1L), true, "player-join"));
        }

        // A slow CI host may hit the 2 ms per-tick cutoff after one request.
        // Require bounded eventual progress, not exactly four admissions per tick.
        for (int turn = 0; turn < 32 && listener.queuedPeriodicChunkCount() > 0; turn++) {
            listener.drain();
        }
        assertEquals(0L, metrics.snapshot().scanEventCompleted(),
                "Offline random player UUIDs are inaccessible, not completed scans");
        assertEquals(0L, metrics.snapshot().scanPeriodicCompleted(),
                "MockBukkit does not prove a full accessible chunk scan");
        assertEquals(0, listener.queuedPeriodicChunkCount(),
                "Routine scanning must be serviced despite the event backlog");
        assertTrue(listener.queuedScanCount() < 13,
                "At least one queued work item must make progress");
    }

    @Test
    void boundedDrainsCaptureEventAndRoutineCostWithoutChangingAdmissions() {
        listener.enqueue(PaperTrackingScanRequest.player(
                UUID.randomUUID(), true, "player-join"));
        // A nonexistent world makes the periodic outcome deterministically skipped.
        listener.enqueue(new PaperTrackingScanRequest(
                null, false,
                new PaperTrackingScanRequest.ChunkReference(UUID.randomUUID(), 0, 0),
                TrackingObservationUseCase.Presence.PRESENT, PERIODIC_SOURCE));

        // The 2 ms time budget may end a tick after one request. Do not require
        // both requests to complete in a single drain under a slow CI host.
        for (int turn = 0; turn < 4 && listener.queuedScanCount() > 0; turn++) {
            listener.drain();
        }

        assertEquals(0, listener.queuedScanCount());
        assertEquals(0L, metrics.snapshot().scanEventBacklog());
        assertEquals(0L, metrics.snapshot().scanPeriodicBacklog());
        assertEquals(2L, metrics.snapshot().scanBacklogPeak());
        // #93's TrackingMetrics projection lacks explicit skipped counters;
        // inaccessibility must never be counted as a completed scan.
        assertEquals(0L, metrics.snapshot().scanEventCompleted());
        assertEquals(0L, metrics.snapshot().scanPeriodicCompleted());
        assertEquals(0L, metrics.snapshot().scanEventRejected());
        assertEquals(0L, metrics.snapshot().scanPeriodicRejected());
    }

    @Test
    void closeReleasesAllPendingPeriodicKeys() {
        World world = server.addSimpleWorld("world");
        listener.enqueue(PaperTrackingScanRequest.chunk(
                world.getChunkAt(0, 0), TrackingObservationUseCase.Presence.PRESENT,
                PERIODIC_SOURCE));
        assertEquals(1, listener.queuedPeriodicChunkCount());

        listener.close();

        assertEquals(0, listener.queuedScanCount());
        assertEquals(0, listener.queuedPeriodicChunkCount());
    }
    @Test
    void naturalLoadOverCapacityStaysMarkedDirtyInsteadOfDisappearing() {
        World world = server.addSimpleWorld("loaded");
        var key = new PaperTrackingScanRequest.ChunkReference(world.getUID(), 0, 0);
        for (int i = 0; i < 1_000; i++) {
            listener.observeNaturalChunkLoad(key, "chunk-load");
        }
        assertEquals(1, listener.queuedScanCount());

        for (int i = 1; i < 1_200; i++) {
            listener.observeNaturalChunkLoad(
                    PaperChunkLoadLedger.key(world.getUID(), i, 0),
                    "chunk-load");
        }
        assertEquals(128, listener.queuedScanCount());
        assertEquals(1_072, listener.dirtyChunkCount());
        assertEquals(1_072L, metrics.snapshot().scanChunkRejected());
    }
    @Test
    void recordsPeakTimingForScannedAndSkippedQueuedRequestOutcomes() {
        World world = server.addSimpleWorld("world");
        listener.enqueue(PaperTrackingScanRequest.player(
                server.addPlayer().getUniqueId(), true, "player-join"));
        listener.enqueue(PaperTrackingScanRequest.player(
                UUID.randomUUID(), true, "player-join"));
        listener.enqueue(PaperTrackingScanRequest.chunk(
                world.getChunkAt(8, 0), TrackingObservationUseCase.Presence.PRESENT, "chunk-load"));
        listener.enqueue(PaperTrackingScanRequest.chunk(
                world.getChunkAt(9, 0), TrackingObservationUseCase.Presence.PRESENT, PERIODIC_SOURCE));

        // Two-millisecond drain bound is per tick: test eventual progress, not one tick.
        for (int turn = 0; turn < 16 && listener.queuedScanCount() > 0; turn++) {
            listener.drain();
        }


        TrackingMetrics.Snapshot result = metrics.snapshot();
        assertTrue(result.scanPlayerPeakNanos() > 0);
        assertTrue(result.scanChunkPeakNanos() > 0);
        assertTrue(result.scanPeriodicPeakNanos() > 0);
        assertTrue(result.scanDrainPeakNanos() >= result.scanChunkPeakNanos());
        assertEquals(4L, result.scanEventCompleted() + result.scanEventSkipped()
                + result.scanEventFailed() + result.scanPeriodicCompleted()
                + result.scanPeriodicSkipped() + result.scanPeriodicFailed());
        assertEquals(1L, result.scanEventSkipped());
        assertEquals(0, listener.queuedScanCount());

        listener.drain();
        assertTrue(metrics.snapshot().scanDrainPeakNanos() >= result.scanDrainPeakNanos());
    }

    @Test
    void recordsDrainTimingWhenScanFailsButDoesNotAbortLaterRequests() {
        listener.enqueue(new PaperTrackingScanRequest(
                null, false, null, TrackingObservationUseCase.Presence.PRESENT,
                "malformed-test-request"));
        listener.enqueue(PaperTrackingScanRequest.player(
                server.addPlayer().getUniqueId(), true, "player-join"));

        // Two-millisecond drain bound is per tick: test eventual progress, not one tick.
        for (int turn = 0; turn < 16 && listener.queuedScanCount() > 0; turn++) {
            listener.drain();
        }


        assertTrue(metrics.snapshot().scanDrainPeakNanos() > 0);
        assertEquals(1L, metrics.snapshot().scanEventFailed());
        assertEquals(1L, metrics.snapshot().scanEventCompleted());
        assertEquals(0, listener.queuedScanCount());
    }

    @Test
    void missingWorldChunkIsSkippedWithoutLoadingOrReportingCompletion() {
        listener.enqueue(new PaperTrackingScanRequest(
                null, false, new PaperTrackingScanRequest.ChunkReference(
                        UUID.randomUUID(), 12, -15),
                TrackingObservationUseCase.Presence.PRESENT, "chunk-load"));
        listener.drain();

        assertEquals(0L, metrics.snapshot().scanEventCompleted());
        assertEquals(1L, metrics.snapshot().scanEventSkipped());
        assertEquals(0L, metrics.snapshot().scanEventFailed());
        assertTrue(metrics.snapshot().scanChunkPeakNanos() > 0L);
        assertEquals(0, listener.queuedScanCount());
    }

    @Test
    void offlinePlayerIsSkippedRatherThanCountedAsPhysicalEvidence() {
        listener.enqueue(PaperTrackingScanRequest.player(
                UUID.randomUUID(), true, "player-join"));
        listener.drain();
        assertEquals(0L, metrics.snapshot().scanEventCompleted());
        assertEquals(1L, metrics.snapshot().scanEventSkipped());
        assertEquals(0L, metrics.snapshot().scanEventFailed());
        assertEquals(0, listener.queuedScanCount());
    }

    @Test
    void malformedQueuedScanCannotAbortFollowingLifecycleObservations() {
        listener.enqueue(new PaperTrackingScanRequest(
                null, false, null, TrackingObservationUseCase.Presence.PRESENT,
                "malformed-test-request"));
        listener.enqueue(PaperTrackingScanRequest.player(
                server.addPlayer().getUniqueId(), true, "player-join"));

        // Two-millisecond drain bound is per tick: test eventual progress, not one tick.
        for (int turn = 0; turn < 16 && listener.queuedScanCount() > 0; turn++) {
            listener.drain();
        }


        assertEquals(0, listener.queuedScanCount());
        assertEquals(1L, metrics.snapshot().scanEventFailed());
        assertEquals(1L, metrics.snapshot().scanEventCompleted());
        assertEquals(0L, metrics.snapshot().scanPeriodicFailed());
        assertEquals(0L, metrics.snapshot().scanEventSkipped());
        assertEquals(0L, metrics.snapshot().failed());
        listener.drain();
        assertEquals(1L, metrics.snapshot().scanEventFailed(), "Failed work must not replay forever");
    }

}
