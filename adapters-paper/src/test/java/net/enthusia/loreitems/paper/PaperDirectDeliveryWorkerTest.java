package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.DirectDeliveryExecutionUseCase;
import net.enthusia.loreitems.application.EncodedItemTemplate;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PreparedDirectDelivery;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class PaperDirectDeliveryWorkerTest {
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
    void synchronousOfflineDeferralFailureMovesClaimToReview() {
        RecordingUseCase useCase = new RecordingUseCase();
        useCase.claimedPage = page(delivery(UUID.fromString(
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")));
        useCase.throwOnDefer = true;
        Plugin plugin = MockBukkit.createMockPlugin();
        worker = new PaperDirectDeliveryWorker(
                plugin,
                useCase,
                new PaperDirectDeliveryOperator(),
                8,
                4);

        worker.start();
        server.getScheduler().performOneTick();
        server.getScheduler().performOneTick();

        assertTrue(useCase.reviewCalled);
        assertTrue(useCase.reviewReason.contains("deferral could not be submitted"));
    }

    @Test
    void durableCompletionFailureAfterPhysicalApplyMovesClaimToReview() {
        RecordingUseCase useCase = new RecordingUseCase();
        PlayerMock player = server.addPlayer();
        EncodedItemTemplate encoded = new PaperItemTemplateCodec()
                .encode(ItemStack.of(Material.DIAMOND));
        useCase.claimedPage = page(delivery(player.getUniqueId(), encoded));
        useCase.failComplete = true;
        Plugin plugin = MockBukkit.createMockPlugin();
        worker = new PaperDirectDeliveryWorker(
                plugin,
                useCase,
                new PaperDirectDeliveryOperator(),
                8,
                4);

        worker.start();
        server.getScheduler().performOneTick();
        server.getScheduler().performOneTick();

        assertEquals(1, useCase.completeCalls);
        assertFalse(player.getInventory().isEmpty());
        assertTrue(useCase.reviewCalled);
        assertTrue(useCase.reviewReason.contains("item was inserted"));
    }

    @Test
    void nullClaimPageReleasesTheWorkerForTheNextBoundedPoll() {
        RecordingUseCase useCase = new RecordingUseCase();
        useCase.returnNullClaimPage = true;
        Plugin plugin = MockBukkit.createMockPlugin();
        worker = new PaperDirectDeliveryWorker(
                plugin,
                useCase,
                new PaperDirectDeliveryOperator(),
                8,
                4);

        worker.start();
        server.getScheduler().performOneTick();
        server.getScheduler().performOneTick();
        for (int tick = 0; tick < 100; tick++) {
            server.getScheduler().performOneTick();
        }

        assertEquals(2, useCase.claimCalls);
    }

    private static Page<PreparedDirectDelivery> page(PreparedDirectDelivery delivery) {
        return new Page<>(List.of(delivery), 0, 4, false);
    }

    private static PreparedDirectDelivery delivery(UUID playerId) {
        return delivery(playerId, new EncodedItemTemplate(1, new byte[] {1}));
    }

    private static PreparedDirectDelivery delivery(
            UUID playerId,
            EncodedItemTemplate template) {
        return new PreparedDirectDelivery(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                new LoreInstanceId(UUID.fromString(
                        "22222222-2222-2222-2222-222222222222")),
                new LoreDefinitionId(UUID.fromString(
                        "33333333-3333-3333-3333-333333333333")),
                playerId,
                new TemplateRevision(1),
                template,
                "delivery-key",
                "claim-token",
                2_000L,
                1,
                500L,
                1_000L);
    }

    private static final class RecordingUseCase
            implements DirectDeliveryExecutionUseCase {
        private Page<PreparedDirectDelivery> claimedPage =
                new Page<>(List.of(), 0, 4, false);
        private boolean returnNullClaimPage;
        private boolean throwOnDefer;
        private boolean failComplete;
        private boolean reviewCalled;
        private String reviewReason;
        private int claimCalls;
        private int completeCalls;

        @Override
        public CompletionStage<Integer> recoverExpiredClaims(int limit) {
            return CompletableFuture.completedFuture(0);
        }

        @Override
        public CompletionStage<Page<PreparedDirectDelivery>> claimPending(int limit) {
            claimCalls++;
            if (returnNullClaimPage) {
                return CompletableFuture.completedFuture(null);
            }
            return CompletableFuture.completedFuture(claimedPage);
        }

        @Override
        public CompletionStage<Boolean> defer(
                PreparedDirectDelivery delivery,
                Duration delay) {
            if (throwOnDefer) {
                throw new IllegalStateException("database unavailable");
            }
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletionStage<Boolean> complete(
                PreparedDirectDelivery delivery,
                int inventorySlot,
                String afterFingerprint) {
            completeCalls++;
            if (failComplete) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("simulated crash after Paper apply"));
            }
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletionStage<Boolean> requireReview(
                PreparedDirectDelivery delivery,
                String reason) {
            reviewCalled = true;
            reviewReason = reason;
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletionStage<Integer> wakePlayer(UUID playerId, int limit) {
            return CompletableFuture.completedFuture(0);
        }
    }
}
