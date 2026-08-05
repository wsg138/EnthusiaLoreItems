package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.ItemIdentityReadResult;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.PreparedTemplateUpdate;
import net.enthusia.loreitems.application.TemplateUpdateExecutionUseCase;
import net.enthusia.loreitems.application.TemplateUpdatePrepareResult;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class PaperTemplateUpdateCoordinatorTest {
    private static final LoreDefinitionId DEFINITION_ID = new LoreDefinitionId(
            UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final LoreInstanceId INSTANCE_ID = new LoreInstanceId(
            UUID.fromString("22222222-2222-2222-2222-222222222222"));
    private static final TemplateRevision REVISION_ONE = new TemplateRevision(1L);
    private static final TemplateRevision REVISION_TWO = new TemplateRevision(2L);
    private static final long CLAIM_EXPIRY = 30_000L;

    private ServerMock server;
    private PlayerMock player;
    private Plugin plugin;
    private PaperItemIdentityCodec identityCodec;
    private PaperTemplateUpdateCoordinator coordinatorUnderTest;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer();
        plugin = MockBukkit.createMockPlugin();
        identityCodec = new PaperItemIdentityCodec();
    }

    @AfterEach
    void tearDown() {
        if (coordinatorUnderTest != null) {
            coordinatorUnderTest.close();
        }
        MockBukkit.unmock();
    }

    @Test
    void expiredClaimNeverReachesThePhysicalMutationAndReleasesItsSlot() {
        ItemStack original = identityCodec.writeIdentity(
                named(Material.DIAMOND_SWORD, "Old Blade"),
                observedIdentity());
        player.getInventory().setItem(0, original);
        PreparedTemplateUpdate update = preparedUpdate();
        RecordingUseCase useCase = new RecordingUseCase(update);
        coordinatorUnderTest = coordinator(useCase, CLAIM_EXPIRY + 1L);
        PaperTemplateUpdateScanner.Candidate candidate = candidate();

        assertTrue(coordinatorUnderTest.submit(candidate));
        assertTrue(coordinatorUnderTest.submit(candidate));

        ItemStack stored = Objects.requireNonNull(player.getInventory().getItem(0));
        assertEquals(Component.text("Old Blade"), stored.getItemMeta().displayName());
        assertEquals(observedIdentity(), readIdentity(stored));
        assertEquals(2, useCase.prepareCalls);
        assertEquals(0, useCase.releaseCalls);
        assertEquals(0, useCase.completeCalls);
        assertEquals(0, useCase.reviewCalls);
    }

    @Test
    void deduplicatesAnInstanceWhilePreparationRemainsInFlight() {
        ItemStack original = identityCodec.writeIdentity(
                named(Material.DIAMOND_SWORD, "Old Blade"),
                observedIdentity());
        player.getInventory().setItem(0, original);
        RecordingUseCase useCase = new RecordingUseCase(preparedUpdate());
        CompletableFuture<TemplateUpdatePrepareResult> pending = new CompletableFuture<>();
        useCase.pendingPreparations.addLast(pending);
        coordinatorUnderTest = coordinator(useCase, CLAIM_EXPIRY - 1L);
        PaperTemplateUpdateScanner.Candidate candidate = candidate();

        assertTrue(coordinatorUnderTest.submit(candidate));
        assertTrue(coordinatorUnderTest.submit(candidate));
        assertEquals(1, useCase.prepareCalls);

        pending.complete(TemplateUpdatePrepareResult.prepared(preparedUpdate()));
        server.getScheduler().performOneTick();

        ItemStack stored = Objects.requireNonNull(player.getInventory().getItem(0));
        assertEquals(Component.text("New Blade"), stored.getItemMeta().displayName());
        assertEquals(targetIdentity(), readIdentity(stored));
        assertEquals(1, useCase.completeCalls);
        assertEquals(0, useCase.releaseCalls);
        assertEquals(0, useCase.reviewCalls);

        assertTrue(coordinatorUnderTest.submit(candidate));
        assertEquals(2, useCase.prepareCalls);
    }

    private PaperTemplateUpdateCoordinator coordinator(
            RecordingUseCase useCase,
            long now) {
        return new PaperTemplateUpdateCoordinator(
                plugin,
                useCase,
                new PaperTemplateUpdateOperator(),
                1,
                Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC));
    }

    private PaperTemplateUpdateScanner.Candidate candidate() {
        return new PaperTemplateUpdateScanner.Candidate(
                observedIdentity(),
                PaperTemplateUpdateItemReference.root(
                        new PaperInventoryReference.PlayerMain(player.getUniqueId()),
                        0));
    }

    private PreparedTemplateUpdate preparedUpdate() {
        return new PreparedTemplateUpdate(
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                "claim-token",
                observedIdentity(),
                targetIdentity(),
                new PaperItemTemplateCodec().encode(
                        named(Material.DIAMOND_SWORD, "New Blade")),
                CLAIM_EXPIRY);
    }

    private LoreItemIdentity readIdentity(ItemStack item) {
        ItemIdentityReadResult.Tracked tracked = assertInstanceOf(
                ItemIdentityReadResult.Tracked.class, identityCodec.readIdentity(item));
        return tracked.identity();
    }

    private static ItemStack named(Material material, String name) {
        ItemStack item = ItemStack.of(material);
        ItemMeta meta = Objects.requireNonNull(item.getItemMeta());
        meta.displayName(Component.text(name));
        assertTrue(item.setItemMeta(meta));
        return item;
    }

    private static LoreItemIdentity observedIdentity() {
        return new LoreItemIdentity(DEFINITION_ID, INSTANCE_ID, REVISION_ONE);
    }

    private static LoreItemIdentity targetIdentity() {
        return new LoreItemIdentity(DEFINITION_ID, INSTANCE_ID, REVISION_TWO);
    }

    private static final class RecordingUseCase
            implements TemplateUpdateExecutionUseCase {
        private final PreparedTemplateUpdate update;
        private final Deque<CompletableFuture<TemplateUpdatePrepareResult>>
                pendingPreparations = new ArrayDeque<>();
        private int prepareCalls;
        private int releaseCalls;
        private int completeCalls;
        private int reviewCalls;

        private RecordingUseCase(PreparedTemplateUpdate update) {
            this.update = Objects.requireNonNull(update, "update");
        }

        @Override
        public CompletionStage<TemplateUpdatePrepareResult> prepare(
                LoreItemIdentity observedIdentity) {
            prepareCalls++;
            CompletableFuture<TemplateUpdatePrepareResult> pending =
                    pendingPreparations.pollFirst();
            if (pending != null) {
                return pending;
            }
            return CompletableFuture.completedFuture(
                    TemplateUpdatePrepareResult.prepared(update));
        }

        @Override
        public CompletionStage<Boolean> release(
                PreparedTemplateUpdate preparedUpdate,
                String reason) {
            releaseCalls++;
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletionStage<Boolean> complete(
                PreparedTemplateUpdate preparedUpdate,
                String beforeFingerprint,
                String afterFingerprint) {
            completeCalls++;
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletionStage<Boolean> requireReview(
                PreparedTemplateUpdate preparedUpdate,
                String reason,
                String beforeFingerprint,
                String afterFingerprint) {
            reviewCalls++;
            return CompletableFuture.completedFuture(true);
        }
    }
}
