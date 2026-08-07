package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.DistributionDeliveryExecutionUseCase;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PreparedDistributionDelivery;
import net.enthusia.loreitems.domain.CampaignRecipient;
import net.enthusia.loreitems.domain.CampaignRecipientKey;
import net.enthusia.loreitems.domain.CampaignRecipientState;
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

class PaperDistributionDeliveryWorkerTest {
    private static final UUID CAMPAIGN_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DEFINITION_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    private ServerMock server;
    private PaperDistributionDeliveryWorker worker;

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
    void offlineRecipientIsDurablyDeferredWithoutPreparation() {
        UUID playerId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        RecordingUseCase useCase = new RecordingUseCase();
        useCase.claimedPage = page(claimed(playerId));
        start(useCase, 8, 4);

        performTicks(3);

        assertEquals(CampaignRecipientState.QUEUED_OFFLINE, useCase.deferredState);
        assertFalse(useCase.prepareCalled);
        assertEquals(4, useCase.lastClaimLimit);
        assertEquals(4, useCase.lastRecoveryLimit);
    }

    @Test
    void fullInventoryIsDeferredWithoutPreparingOrMutatingStorage() {
        PlayerMock player = server.addPlayer();
        ItemStack filler = ItemStack.of(Material.COBBLESTONE, 64);
        for (int slot = 0; slot < player.getInventory().getStorageContents().length; slot++) {
            player.getInventory().setItem(slot, filler.clone());
        }
        RecordingUseCase useCase = new RecordingUseCase();
        useCase.claimedPage = page(claimed(player.getUniqueId()));
        start(useCase, 8, 4);

        performTicks(3);

        assertEquals(CampaignRecipientState.QUEUED_INVENTORY_FULL, useCase.deferredState);
        assertFalse(useCase.prepareCalled);
        for (ItemStack item : player.getInventory().getStorageContents()) {
            assertNotNull(item);
            assertEquals(Material.COBBLESTONE, item.getType());
            assertEquals(64, item.getAmount());
        }
    }

    @Test
    void onlineRecipientIsPreparedInsertedAndCompletedOnce() {
        PlayerMock player = server.addPlayer();
        RecordingUseCase useCase = new RecordingUseCase();
        CampaignRecipient claimed = claimed(player.getUniqueId());
        useCase.claimedPage = page(claimed);
        useCase.prepared = prepared(claimed, ItemStack.of(Material.DIAMOND));
        start(useCase, 8, 4);

        performTicks(5);

        assertTrue(useCase.prepareCalled);
        assertEquals(1, useCase.completeCalls);
        assertTrue(useCase.lastFingerprint != null && useCase.lastFingerprint.length() == 64);
        long diamonds = 0L;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && item.getType() == Material.DIAMOND) {
                diamonds += item.getAmount();
            }
        }
        assertEquals(1L, diamonds);
    }

    @Test
    void inFlightRecoveryPreventsOverlappingPeriodicClaims() {
        RecordingUseCase useCase = new RecordingUseCase();
        useCase.recoveryFuture = new CompletableFuture<>();
        start(useCase, 8, 4);

        performTicks(205);

        assertEquals(1, useCase.recoveryCalls);
        assertEquals(0, useCase.claimCalls);
    }

    @Test
    void playerWakeUsesTheSameBoundedClaimLimit() {
        RecordingUseCase useCase = new RecordingUseCase();
        start(useCase, 12, 3);
        UUID playerId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

        worker.wakePlayer(playerId);

        assertEquals(playerId, useCase.lastWakePlayer);
        assertEquals(3, useCase.lastWakeLimit);
    }

    private void start(RecordingUseCase useCase, int batchSize, int mutationBudget) {
        Plugin plugin = MockBukkit.createMockPlugin();
        worker = new PaperDistributionDeliveryWorker(
                plugin,
                useCase,
                new PaperDirectDeliveryOperator(),
                batchSize,
                mutationBudget);
        worker.start();
    }

    private void performTicks(int count) {
        for (int tick = 0; tick < count; tick++) {
            server.getScheduler().performOneTick();
        }
    }

    private static Page<CampaignRecipient> page(CampaignRecipient recipient) {
        return new Page<>(List.of(recipient), 0, 4, false);
    }

    private static CampaignRecipient claimed(UUID playerId) {
        return new CampaignRecipient(
                CAMPAIGN_ID,
                CampaignRecipientKey.forPlayer(playerId),
                0,
                playerId.toString(),
                playerId,
                CampaignRecipientState.RESERVED_IN_FLIGHT,
                null,
                "claim-token",
                5_000L,
                1,
                null,
                null,
                1_000L);
    }

    private static PreparedDistributionDelivery prepared(
            CampaignRecipient claimed, ItemStack templateItem) {
        return new PreparedDistributionDelivery(
                claimed.campaignId(),
                claimed.recipientKey(),
                new LoreInstanceId(UUID.fromString(
                        "33333333-3333-3333-3333-333333333333")),
                new LoreDefinitionId(DEFINITION_ID),
                claimed.playerId(),
                new TemplateRevision(1),
                new PaperItemTemplateCodec().encode(templateItem),
                claimed.claimToken(),
                claimed.claimExpiresAtEpochMillis(),
                claimed.attemptCount(),
                claimed.updatedAtEpochMillis());
    }

    private static final class RecordingUseCase
            implements DistributionDeliveryExecutionUseCase {
        private Page<CampaignRecipient> claimedPage = new Page<>(List.of(), 0, 4, false);
        private PreparedDistributionDelivery prepared;
        private CompletionStage<Integer> recoveryFuture = CompletableFuture.completedFuture(0);
        private CampaignRecipientState deferredState;
        private boolean prepareCalled;
        private int completeCalls;
        private String lastFingerprint;
        private int recoveryCalls;
        private int claimCalls;
        private int lastClaimLimit;
        private int lastRecoveryLimit;
        private UUID lastWakePlayer;
        private int lastWakeLimit;

        @Override
        public CompletionStage<Page<CampaignRecipient>> claimPending(int limit) {
            claimCalls++;
            lastClaimLimit = limit;
            Page<CampaignRecipient> result = claimedPage;
            claimedPage = new Page<>(List.of(), 0, 4, false);
            return CompletableFuture.completedFuture(result);
        }

        @Override
        public CompletionStage<Optional<PreparedDistributionDelivery>> prepare(
                CampaignRecipient recipient) {
            prepareCalled = true;
            return CompletableFuture.completedFuture(Optional.ofNullable(prepared));
        }

        @Override
        public CompletionStage<Boolean> defer(
                CampaignRecipient recipient,
                CampaignRecipientState targetPendingState,
                Duration delay) {
            deferredState = targetPendingState;
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletionStage<Boolean> defer(
                PreparedDistributionDelivery delivery,
                CampaignRecipientState targetPendingState,
                Duration delay) {
            deferredState = targetPendingState;
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletionStage<Boolean> cancel(CampaignRecipient recipient) {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletionStage<Boolean> cancel(PreparedDistributionDelivery delivery) {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletionStage<Boolean> complete(
                PreparedDistributionDelivery delivery,
                int inventorySlot,
                String afterFingerprint) {
            completeCalls++;
            lastFingerprint = afterFingerprint;
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletionStage<Boolean> requireReview(
                CampaignRecipient recipient, String reason) {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletionStage<Boolean> requireReview(
                PreparedDistributionDelivery delivery, String reason) {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletionStage<Integer> wakePlayer(UUID playerId, int limit) {
            lastWakePlayer = playerId;
            lastWakeLimit = limit;
            return CompletableFuture.completedFuture(0);
        }

        @Override
        public CompletionStage<Integer> recoverExpiredClaims(int limit) {
            recoveryCalls++;
            lastRecoveryLimit = limit;
            return recoveryFuture;
        }
    }
}
