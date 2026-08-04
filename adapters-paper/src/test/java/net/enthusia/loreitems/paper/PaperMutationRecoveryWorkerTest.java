package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.application.PendingMutationRecord;
import net.enthusia.loreitems.application.PendingMutationRepository;
import net.enthusia.loreitems.domain.PendingMutationState;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class PaperMutationRecoveryWorkerTest {
    private ServerMock server;
    private PaperMutationRecoveryWorker worker;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        if (worker != null) {
            worker.close();
        }
        MockBukkit.unmock();
    }

    @Test
    void scheduledRecoveryContinuesInBoundedBatches() {
        RecordingRepository repository = new RecordingRepository();
        Plugin plugin = MockBukkit.createMockPlugin();
        worker = new PaperMutationRecoveryWorker(plugin, repository, 7);

        worker.start();
        server.getScheduler().performOneTick();
        for (int tick = 0; tick < 100; tick++) {
            server.getScheduler().performOneTick();
        }

        assertEquals(2, repository.recoveryCalls);
        assertEquals(7, repository.lastLimit);
    }

    @Test
    void doesNotOverlapAnInFlightRecovery() {
        RecordingRepository repository = new RecordingRepository();
        repository.blockFirst = true;
        Plugin plugin = MockBukkit.createMockPlugin();
        worker = new PaperMutationRecoveryWorker(plugin, repository, 4);

        worker.requestRun();
        worker.requestRun();
        assertEquals(1, repository.recoveryCalls);

        repository.firstRecovery.complete(1);
        worker.requestRun();
        assertEquals(2, repository.recoveryCalls);
    }

    private static final class RecordingRepository implements PendingMutationRepository {
        private final CompletableFuture<Integer> firstRecovery = new CompletableFuture<>();
        private boolean blockFirst;
        private int recoveryCalls;
        private int lastLimit;

        @Override
        public CompletionStage<Integer> moveExpiredClaimsToReview(
                Instant now,
                int limit) {
            recoveryCalls++;
            lastLimit = limit;
            if (blockFirst && recoveryCalls == 1) {
                return firstRecovery;
            }
            return CompletableFuture.completedFuture(0);
        }

        @Override
        public CompletionStage<Void> insert(PendingMutationRecord mutation) {
            return unsupported();
        }

        @Override
        public CompletionStage<Page<PendingMutationRecord>> claimPending(
                String mutationType,
                String claimToken,
                Instant now,
                Duration lease,
                int limit) {
            return unsupported();
        }

        @Override
        public CompletionStage<Boolean> transitionClaimed(
                UUID mutationId,
                PendingMutationState expected,
                PendingMutationState target,
                String claimToken,
                Instant now) {
            return unsupported();
        }

        @Override
        public CompletionStage<Page<PendingMutationRecord>> listNonTerminal(
                PageRequest request) {
            return unsupported();
        }

        @Override
        public CompletionStage<Page<PendingMutationRecord>> listNonTerminal(
                String mutationType, PageRequest request) {
            return unsupported();
        }

        private static <T> CompletionStage<T> unsupported() {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }
    }
}
