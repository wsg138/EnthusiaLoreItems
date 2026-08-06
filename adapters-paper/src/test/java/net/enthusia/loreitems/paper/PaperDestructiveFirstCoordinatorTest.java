package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.DestructiveRemovalExecutionUseCase;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.PreparedTemplateUpdate;
import net.enthusia.loreitems.application.TemplateUpdateExecutionUseCase;
import net.enthusia.loreitems.application.TemplateUpdatePrepareResult;
import net.enthusia.loreitems.domain.DestructiveEffectState;
import net.enthusia.loreitems.domain.DestructiveOperationType;
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

class PaperDestructiveFirstCoordinatorTest {
    private static final LoreDefinitionId DEFINITION_ID = new LoreDefinitionId(
            UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final LoreInstanceId INSTANCE_ID = new LoreInstanceId(
            UUID.fromString("22222222-2222-2222-2222-222222222222"));
    private static final LoreItemIdentity IDENTITY =
            new LoreItemIdentity(DEFINITION_ID, INSTANCE_ID, new TemplateRevision(1L));
    private static final long NOW = 10_000L;

    private ServerMock server;
    private PlayerMock player;
    private Plugin plugin;
    private PaperItemIdentityCodec identityCodec;
    private PaperTemplateUpdateCoordinator coordinator;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer();
        plugin = MockBukkit.createMockPlugin();
        identityCodec = new PaperItemIdentityCodec();
    }

    @AfterEach
    void tearDown() {
        if (coordinator != null) {
            coordinator.close();
        }
        MockBukkit.unmock();
    }

    @Test
    void destructiveRemovalCompletesBeforeTemplatePreparationCanRun() {
        player.getInventory().setItem(
                0,
                identityCodec.writeIdentity(
                        ItemStack.of(Material.DIAMOND_SWORD), IDENTITY));
        RecordingTemplateUseCase templateUseCase = new RecordingTemplateUseCase();
        RecordingDestructiveUseCase destructiveUseCase = new RecordingDestructiveUseCase();
        coordinator = new PaperTemplateUpdateCoordinator(
                plugin,
                templateUseCase,
                new PaperTemplateUpdateOperator(),
                destructiveUseCase,
                new PaperDestructiveRemovalOperator(),
                1,
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));

        assertTrue(coordinator.submit(candidate()));
        server.getScheduler().performOneTick();
        server.getScheduler().performOneTick();

        assertEquals(1, destructiveUseCase.prepareCalls);
        assertEquals(1, destructiveUseCase.completeCalls);
        assertEquals(0, templateUseCase.prepareCalls);
        assertNull(player.getInventory().getItem(0));
    }

    private PaperTemplateUpdateScanner.Candidate candidate() {
        return new PaperTemplateUpdateScanner.Candidate(
                IDENTITY,
                PaperTemplateUpdateItemReference.root(
                        new PaperInventoryReference.PlayerMain(player.getUniqueId()),
                        0));
    }

    private static final class RecordingTemplateUseCase
            implements TemplateUpdateExecutionUseCase {
        private int prepareCalls;

        @Override
        public CompletionStage<TemplateUpdatePrepareResult> prepare(
                LoreItemIdentity observedIdentity) {
            prepareCalls++;
            return CompletableFuture.completedFuture(
                    TemplateUpdatePrepareResult.noPendingWork());
        }

        @Override
        public CompletionStage<Boolean> release(
                PreparedTemplateUpdate preparedUpdate,
                String reason) {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletionStage<Boolean> complete(
                PreparedTemplateUpdate preparedUpdate,
                String beforeFingerprint,
                String afterFingerprint) {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletionStage<Boolean> requireReview(
                PreparedTemplateUpdate preparedUpdate,
                String reason,
                String beforeFingerprint,
                String afterFingerprint) {
            return CompletableFuture.completedFuture(true);
        }
    }

    private static final class RecordingDestructiveUseCase
            implements DestructiveRemovalExecutionUseCase {
        private int prepareCalls;
        private int completeCalls;

        @Override
        public CompletionStage<PrepareResult> prepare(Observation observation) {
            prepareCalls++;
            return CompletableFuture.completedFuture(PrepareResult.prepared(
                    new PreparedRemoval(
                            UUID.fromString("33333333-3333-3333-3333-333333333333"),
                            DestructiveOperationType.EXACT_INSTANCE_REMOVAL,
                            DEFINITION_ID,
                            INSTANCE_ID,
                            IDENTITY.appliedRevision(),
                            IDENTITY,
                            observation.locationType(),
                            observation.locationKey(),
                            observation.containerPath(),
                            observation.fingerprint(),
                            "claim-token",
                            NOW + 30_000L)));
        }

        @Override
        public CompletionStage<Boolean> release(PreparedRemoval removal, String reason) {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletionStage<Boolean> complete(
                PreparedRemoval removal,
                String beforeFingerprint) {
            completeCalls++;
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletionStage<Boolean> requireReview(
                PreparedRemoval removal,
                DestructiveEffectState effectState,
                String beforeFingerprint,
                String afterFingerprint,
                String detail) {
            return CompletableFuture.completedFuture(true);
        }
    }
}
