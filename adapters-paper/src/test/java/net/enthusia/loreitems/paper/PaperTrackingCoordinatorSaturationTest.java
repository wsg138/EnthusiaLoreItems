package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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

class PaperTrackingCoordinatorSaturationTest {
    private static final LoreItemIdentity IDENTITY = new LoreItemIdentity(
            new LoreDefinitionId(UUID.fromString("11111111-1111-1111-1111-111111111111")),
            new LoreInstanceId(UUID.fromString("22222222-2222-2222-2222-222222222222")),
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
    @Timeout(5)
    void saturationKeepsOneInFlightEightQueuedAndRejectsTheTenthWithMetrics() {
        BlockingUseCase useCase = new BlockingUseCase();
        RecordingMetrics metrics = new RecordingMetrics();
        coordinator = new PaperTrackingCoordinator(plugin, () -> useCase, () -> 1, metrics);

        for (int slot = 0; slot < 9; slot++) {
            assertTrue(coordinator.submit(request(slot)));
        }
        assertFalse(coordinator.submit(request(9)));

        assertEquals(1, useCase.stages.size());
        assertEquals(1L, metrics.gauge("tracking.in_flight"));
        assertEquals(8L, metrics.gauge("tracking.queued"));
        assertEquals(1L, metrics.counter("tracking.rejected"));
        assertEquals(9L, metrics.counter("tracking.accepted"));

        for (int index = 0; index < 9; index++) {
            awaitStageCount(useCase, index + 1);
            useCase.stages.get(index).complete(recorded());
        }

        assertEquals(9L, metrics.counter("tracking.completed"));
        assertEquals(0L, metrics.gauge("tracking.in_flight"));
        assertEquals(0L, metrics.gauge("tracking.queued"));
        assertEquals(9L, metrics.durationCount("tracking.persistence_nanos"));
    }

    private static void awaitStageCount(BlockingUseCase useCase, int expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (useCase.stages.size() < expected && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(expected, useCase.stages.size());
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
                "saturation-test");
    }

    private static TrackingObservationUseCase.Result recorded() {
        return TrackingObservationUseCase.Result.of(
                TrackingObservationUseCase.Status.RECORDED,
                "Recorded.");
    }

    private static final class BlockingUseCase implements TrackingObservationUseCase {
        private final List<CompletableFuture<Result>> stages = new CopyOnWriteArrayList<>();

        @Override
        public CompletionStage<Result> record(Request request) {
            CompletableFuture<Result> stage = new CompletableFuture<>();
            stages.add(stage);
            return stage;
        }
    }

    private static final class RecordingMetrics implements MetricsPort {
        private final Map<String, AtomicLong> gauges = new ConcurrentHashMap<>();
        private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
        private final Map<String, AtomicLong> durationCounts = new ConcurrentHashMap<>();

        @Override
        public void setGauge(String name, long value) {
            gauges.computeIfAbsent(name, ignored -> new AtomicLong()).set(value);
        }

        @Override
        public void increment(String name) {
            counters.computeIfAbsent(name, ignored -> new AtomicLong()).incrementAndGet();
        }

        @Override
        public void recordDurationNanos(String name, long durationNanos) {
            durationCounts.computeIfAbsent(name, ignored -> new AtomicLong()).incrementAndGet();
        }

        long gauge(String name) {
            return gauges.getOrDefault(name, new AtomicLong()).get();
        }

        long counter(String name) {
            return counters.getOrDefault(name, new AtomicLong()).get();
        }

        long durationCount(String name) {
            return durationCounts.getOrDefault(name, new AtomicLong()).get();
        }
    }
}
