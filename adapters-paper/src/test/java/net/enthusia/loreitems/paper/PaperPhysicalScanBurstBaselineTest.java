package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.enthusia.loreitems.application.TrackingMetrics;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

/**
 * Reproduces capacity-saturation observations without altering production
 * queue policy or treating rejected lifecycle work as completed evidence.
 */
class PaperPhysicalScanBurstBaselineTest {
    private static final int CAPACITY = 128; // currentBudget 4 times QUEUE_MULTIPLIER 32
    private static final int BUDGET = 4;
    private static final String CHUNK_LOAD_SOURCE = "chunk-load";
    private ServerMock server;
    private PaperPhysicalTrackingListener listener;
    private TrackingMetrics metrics;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        PluginMock plugin = MockBukkit.createMockPlugin();
        TrackingObservationUseCase useCase = request ->
                CompletableFuture.completedFuture(TrackingObservationUseCase.Result.of(
                        TrackingObservationUseCase.Status.RECORDED, "ok"));
        metrics = new TrackingMetrics();
        listener = new PaperPhysicalTrackingListener(
                plugin, () -> useCase, () -> BUDGET, metrics);
    }

    private static UUID playerId(long index) {
        return new UUID(0L, index);
    }

    @AfterEach
    void tearDown() {
        listener.close();
        MockBukkit.unmock();
    }

    @Test
    void distinctChunkLoadBurstShowsBoundedUnreplayedRejections() {
        World world = server.addSimpleWorld("world");
        for (int index = 0; index < CAPACITY + 8; index++) {
            listener.enqueue(PaperTrackingScanRequest.chunk(
                    world.getChunkAt(index, 0),
                    TrackingObservationUseCase.Presence.PRESENT, CHUNK_LOAD_SOURCE));
        }

        assertEquals(CAPACITY, listener.queuedScanCount());
        assertEquals(CAPACITY, metrics.snapshot().scanBacklogPeak());
        assertEquals(8, metrics.snapshot().scanEventRejected());
        assertEquals(8, metrics.snapshot().scanChunkRejected());
        assertEquals(0, metrics.snapshot().scanPlayerRejected());

        for (int tick = 0; tick < CAPACITY / BUDGET; tick++) {
            listener.drain();
        }

        assertEquals(0, listener.queuedScanCount());
        assertEquals(CAPACITY, metrics.snapshot().scanEventCompleted());
        assertEquals(8, metrics.snapshot().scanChunkRejected(),
                "The original queue has no retry; this records the reproduction, not a fix");
    }

    @Test
    void mixedPlayerAndChunkBurstKeepsIndependentRejectProvenance() {
        World world = server.addSimpleWorld("world");
        for (int index = 0; index < CAPACITY - 2; index++) {
            listener.enqueue(PaperTrackingScanRequest.chunk(
                    world.getChunkAt(index, 1),
                    TrackingObservationUseCase.Presence.PRESENT, CHUNK_LOAD_SOURCE));
        }
        for (int index = 0; index < 2; index++) {
            listener.enqueue(PaperTrackingScanRequest.player(
                    playerId(index + 1L), true, "player-join"));
        }
        listener.enqueue(PaperTrackingScanRequest.player(
                new UUID(0L, 3L), true, "player-join"));
        listener.enqueue(PaperTrackingScanRequest.chunk(
                world.getChunkAt(CAPACITY, 1),
                TrackingObservationUseCase.Presence.PRESENT, CHUNK_LOAD_SOURCE));

        assertEquals(CAPACITY, listener.queuedScanCount());
        assertEquals(2, metrics.snapshot().scanEventRejected());
        assertEquals(1, metrics.snapshot().scanPlayerRejected());
        assertEquals(1, metrics.snapshot().scanChunkRejected());
        assertEquals(0, metrics.snapshot().scanPeriodicRejected());
    }

    @Test
    void droppingOptionalReconciliationCannotEvictCommittedLifecycleWork() {
        World world = server.addSimpleWorld("world");
        for (int index = 0; index < CAPACITY - 1; index++) {
            listener.enqueue(PaperTrackingScanRequest.player(
                    playerId(index + 1L), true, "tracking-start"));
        }
        listener.enqueue(PaperTrackingScanRequest.chunk(
                world.getChunkAt(1, 1),
                TrackingObservationUseCase.Presence.PRESENT, "periodic-loaded-chunk"));
        listener.enqueue(PaperTrackingScanRequest.chunk(
                world.getChunkAt(2, 1),
                TrackingObservationUseCase.Presence.PRESENT, CHUNK_LOAD_SOURCE));

        assertEquals(CAPACITY, listener.queuedScanCount());
        assertEquals(0, listener.queuedPeriodicChunkCount());
        assertEquals(1, metrics.snapshot().scanPeriodicRejected());
        assertEquals(0, metrics.snapshot().scanEventRejected());
    }
}
