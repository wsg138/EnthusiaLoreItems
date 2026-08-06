package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.application.TemplateRevisionRolloutBatchResult;
import net.enthusia.loreitems.application.TemplateRevisionRolloutCandidate;
import net.enthusia.loreitems.application.TemplateRevisionRolloutRequest;
import net.enthusia.loreitems.application.TemplateRevisionRolloutUseCase;
import net.enthusia.loreitems.application.TemplateRevisionStartResult;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class PaperTemplateRevisionPlannerWorkerTest {
    private ServerMock server;
    private Plugin plugin;

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
    void schedulesOnlyOneBoundedBatchPerPassAndContinuesWhenMoreRemain() {
        RecordingUseCase useCase = new RecordingUseCase();
        AtomicInteger executionWakes = new AtomicInteger();
        try (PaperTemplateRevisionPlannerWorker worker =
                     new PaperTemplateRevisionPlannerWorker(
                             plugin, useCase, 7, executionWakes::incrementAndGet)) {
            worker.start();
            for (int tick = 0; tick < 8; tick++) {
                server.getScheduler().performOneTick();
            }

            assertEquals(2, useCase.listCalls);
            assertEquals(2, useCase.scheduleCalls);
            assertEquals(7, useCase.lastBatchLimit);
            assertEquals(2, executionWakes.get());
        }
    }

    @Test
    void inFlightDiscoveryPreventsOverlappingDatabaseQueries() {
        DeferredUseCase useCase = new DeferredUseCase();
        try (PaperTemplateRevisionPlannerWorker worker =
                     new PaperTemplateRevisionPlannerWorker(plugin, useCase, 5)) {
            worker.requestRun();
            worker.requestRun();
            worker.wake();
            server.getScheduler().performOneTick();
            assertEquals(1, useCase.listCalls);

            useCase.discovery.complete(emptyPage());
            worker.requestRun();
            assertEquals(1, useCase.listCalls);
            server.getScheduler().performOneTick();
            worker.requestRun();
            assertEquals(2, useCase.listCalls);
        }
    }

    @Test
    void validatesTheConfiguredBound() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PaperTemplateRevisionPlannerWorker(plugin, new RecordingUseCase(), 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PaperTemplateRevisionPlannerWorker(
                        plugin,
                        new RecordingUseCase(),
                        PageRequest.MAX_LIMIT + 1));
    }

    private static Page<TemplateRevisionRolloutCandidate> emptyPage() {
        return new Page<>(List.of(), 0, 1, false);
    }

    private static final class RecordingUseCase implements TemplateRevisionRolloutUseCase {
        private final TemplateRevisionRolloutCandidate candidate =
                new TemplateRevisionRolloutCandidate(
                        new LoreDefinitionId(UUID.randomUUID()),
                        new TemplateRevision(2));
        private int listCalls;
        private int scheduleCalls;
        private int lastBatchLimit;

        @Override
        public CompletionStage<TemplateRevisionStartResult> start(
                TemplateRevisionRolloutRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<TemplateRevisionRolloutBatchResult> scheduleNextBatch(
                TemplateRevisionRolloutCandidate value, int limit) {
            scheduleCalls++;
            lastBatchLimit = limit;
            return CompletableFuture.completedFuture(scheduleCalls == 1
                    ? TemplateRevisionRolloutBatchResult.scheduled(1, true)
                    : TemplateRevisionRolloutBatchResult.complete(1));
        }

        @Override
        public CompletionStage<Page<TemplateRevisionRolloutCandidate>> listIncomplete(
                PageRequest request) {
            listCalls++;
            return CompletableFuture.completedFuture(
                    listCalls <= 2
                            ? new Page<>(List.of(candidate), 0, request.limit(), false)
                            : emptyPage());
        }
    }

    private static final class DeferredUseCase implements TemplateRevisionRolloutUseCase {
        private CompletableFuture<Page<TemplateRevisionRolloutCandidate>> discovery =
                new CompletableFuture<>();
        private int listCalls;

        @Override
        public CompletionStage<TemplateRevisionStartResult> start(
                TemplateRevisionRolloutRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<TemplateRevisionRolloutBatchResult> scheduleNextBatch(
                TemplateRevisionRolloutCandidate candidate, int limit) {
            throw new AssertionError("No candidate should be scheduled");
        }

        @Override
        public CompletionStage<Page<TemplateRevisionRolloutCandidate>> listIncomplete(
                PageRequest request) {
            listCalls++;
            if (discovery.isDone()) {
                discovery = CompletableFuture.completedFuture(emptyPage());
            }
            return discovery;
        }
    }
}
