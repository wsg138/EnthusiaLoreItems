package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.DirectDeliveryExecutionUseCase;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PreparedDirectDelivery;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class PaperDeliveryWorkerSaturationTest {
    private ServerMock server;
    private PaperDirectDeliveryWorker worker;

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
    void durableClaimWindowUsesSmallerMutationBudgetAndNeverOverlapsPolls() {
        BlockingUseCase useCase = new BlockingUseCase();
        Plugin plugin = MockBukkit.createMockPlugin();
        worker = new PaperDirectDeliveryWorker(
                plugin,
                useCase,
                new PaperDirectDeliveryOperator(),
                200,
                3);

        worker.start();
        server.getScheduler().performOneTick();
        for (int tick = 0; tick < 250; tick++) {
            server.getScheduler().performOneTick();
        }

        assertEquals(1, useCase.recoveryCalls);
        assertEquals(3, useCase.lastRecoveryLimit);
        assertEquals(0, useCase.claimCalls);

        useCase.recovery.complete(0);
        assertEquals(1, useCase.claimCalls);
        assertEquals(3, useCase.lastClaimLimit);

        for (int tick = 0; tick < 250; tick++) {
            server.getScheduler().performOneTick();
        }
        assertEquals(1, useCase.claimCalls);
    }

    private static final class BlockingUseCase implements DirectDeliveryExecutionUseCase {
        private final CompletableFuture<Integer> recovery = new CompletableFuture<>();
        private final CompletableFuture<Page<PreparedDirectDelivery>> claim = new CompletableFuture<>();
        private int recoveryCalls;
        private int claimCalls;
        private int lastRecoveryLimit;
        private int lastClaimLimit;

        @Override
        public CompletionStage<Integer> recoverExpiredClaims(int limit) {
            recoveryCalls++;
            lastRecoveryLimit = limit;
            return recovery;
        }

        @Override
        public CompletionStage<Page<PreparedDirectDelivery>> claimPending(int limit) {
            claimCalls++;
            lastClaimLimit = limit;
            return claim;
        }

        @Override
        public CompletionStage<Boolean> defer(PreparedDirectDelivery delivery, Duration delay) {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletionStage<Boolean> complete(
                PreparedDirectDelivery delivery,
                int inventorySlot,
                String afterFingerprint) {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletionStage<Boolean> requireReview(
                PreparedDirectDelivery delivery,
                String reason) {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletionStage<Integer> wakePlayer(UUID playerId, int limit) {
            return CompletableFuture.completedFuture(0);
        }
    }
}
