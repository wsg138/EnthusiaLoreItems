package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.domain.DefinitionKey;
import net.enthusia.loreitems.domain.DestructiveOperationState;
import net.enthusia.loreitems.domain.DestructiveOperationType;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class LoreItemsDestructiveCommandExecutorTest {
    private static final String COMMAND_LABEL = "loreitems";
    private static final String TOKEN = "purge-token";
    private static final String CONFIRM_PURGE = "confirm-purge";

    private ServerMock server;
    private Plugin plugin;
    private PlayerMock player;
    private RecordingUseCase useCase;
    private AtomicInteger wakes;
    private LoreItemsDestructiveCommandExecutor executor;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        player = server.addPlayer();
        useCase = new RecordingUseCase();
        wakes = new AtomicInteger();
        executor = new LoreItemsDestructiveCommandExecutor(
                plugin, () -> useCase, () -> 10, wakes::incrementAndGet);
    }

    @AfterEach
    void tearDown() {
        executor.close();
        MockBukkit.unmock();
    }

    @Test
    void permissionGatePreventsPreviewSubmission() {
        executor.onCommand(
                player,
                command(),
                COMMAND_LABEL,
                new String[] {"purge", useCase.definitionId.value().toString()});

        assertNull(useCase.previewRequest);
        assertEquals(0, useCase.startCalls);
    }

    @Test
    void matchingOperationConfirmationStartsOnceAndWakesWork() {
        player.addAttachment(plugin, LoreItemsDestructiveCommandExecutor.PURGE_PERMISSION, true);
        previewPurge();

        executor.onCommand(
                player,
                command(),
                COMMAND_LABEL,
                new String[] {CONFIRM_PURGE, TOKEN});
        server.getScheduler().performOneTick();

        assertNotNull(useCase.previewRequest);
        assertEquals(
                DestructiveOperationType.PURGE_DEFINITION,
                useCase.previewRequest.operationType());
        assertEquals(1, useCase.startCalls);
        assertNotNull(useCase.startRequest);
        assertEquals(player.getUniqueId().toString(), useCase.startRequest.actorId());
        assertEquals(1, wakes.get());

        executor.onCommand(
                player,
                command(),
                COMMAND_LABEL,
                new String[] {CONFIRM_PURGE, TOKEN});
        server.getScheduler().performOneTick();
        assertEquals(1, useCase.startCalls);
    }

    @Test
    void wrongOperationDoesNotConsumeTheMatchingConfirmation() {
        player.addAttachment(plugin, LoreItemsDestructiveCommandExecutor.PURGE_PERMISSION, true);
        player.addAttachment(plugin, LoreItemsDestructiveCommandExecutor.DELETE_PERMISSION, true);
        previewPurge();

        executor.onCommand(
                player,
                command(),
                COMMAND_LABEL,
                new String[] {"confirm-delete", TOKEN});
        server.getScheduler().performOneTick();
        assertEquals(0, useCase.startCalls);

        executor.onCommand(
                player,
                command(),
                COMMAND_LABEL,
                new String[] {CONFIRM_PURGE, TOKEN});
        server.getScheduler().performOneTick();
        assertEquals(1, useCase.startCalls);
    }

    @Test
    void reloadCleanupInvalidatesUnconfirmedPreview() {
        player.addAttachment(plugin, LoreItemsDestructiveCommandExecutor.PURGE_PERMISSION, true);
        previewPurge();
        executor.clearConfirmations();

        executor.onCommand(
                player,
                command(),
                COMMAND_LABEL,
                new String[] {CONFIRM_PURGE, TOKEN});
        server.getScheduler().performOneTick();

        assertEquals(0, useCase.startCalls);
        assertEquals(0, wakes.get());
    }

    private void previewPurge() {
        executor.onCommand(
                player,
                command(),
                COMMAND_LABEL,
                new String[] {"purge", useCase.definitionId.value().toString()});
        server.getScheduler().performOneTick();
    }

    private static Command command() {
        return new Command(COMMAND_LABEL) {
            @Override
            public boolean execute(
                    CommandSender sender, String commandLabel, String[] arguments) {
                throw new AssertionError("command must not execute");
            }
        };
    }

    private static final class RecordingUseCase implements DestructiveAdministrationUseCase {
        private final LoreDefinitionId definitionId = LoreDefinitionId.random();
        private final TemplateRevision revision = new TemplateRevision(1L);
        private PreviewRequest previewRequest;
        private StartRequest startRequest;
        private int startCalls;

        @Override
        public CompletionStage<Optional<Preview>> preview(PreviewRequest request) {
            previewRequest = request;
            return CompletableFuture.completedFuture(Optional.of(new Preview(
                    request.operationType(),
                    definitionId,
                    new DefinitionKey("purge-test"),
                    "Purge Test",
                    revision,
                    request.exactInstanceId(),
                    3L,
                    1L,
                    1L,
                    1L,
                    TOKEN)));
        }

        @Override
        public CompletionStage<StartResult> start(StartRequest request) {
            startRequest = request;
            startCalls++;
            OperationView operation = new OperationView(
                    UUID.randomUUID(),
                    request.preview().operationType(),
                    definitionId,
                    request.preview().exactInstanceId(),
                    revision,
                    DestructiveOperationState.ACTIVE,
                    request.actorId(),
                    request.idempotencyKey(),
                    3L,
                    3L,
                    0L,
                    0L,
                    0L,
                    0L,
                    1_000L,
                    1_000L,
                    null);
            return CompletableFuture.completedFuture(StartResult.success(
                    StartStatus.STARTED, operation, "Destructive operation accepted."));
        }

        @Override
        public CompletionStage<Page<OperationView>> listOperations(PageRequest request) {
            return CompletableFuture.completedFuture(emptyPage(request));
        }

        @Override
        public CompletionStage<Page<TargetView>> listTargets(
                UUID operationId, PageRequest request) {
            return CompletableFuture.completedFuture(emptyPage(request));
        }

        @Override
        public CompletionStage<ControlResult> pause(ControlRequest request) {
            return CompletableFuture.completedFuture(
                    new ControlResult(ControlStatus.NOT_FOUND, null, "Not found."));
        }

        @Override
        public CompletionStage<ControlResult> resume(ControlRequest request) {
            return CompletableFuture.completedFuture(
                    new ControlResult(ControlStatus.NOT_FOUND, null, "Not found."));
        }

        @Override
        public CompletionStage<ReviewResult> resolveReview(ReviewRequest request) {
            return CompletableFuture.completedFuture(
                    new ReviewResult(ReviewStatus.NOT_FOUND, null, "Not found."));
        }

        @Override
        public CompletionStage<Metrics> metrics() {
            return CompletableFuture.completedFuture(
                    new Metrics(0L, 0L, 0L, 0L, 0L, 0L, 0L));
        }

        private static <T> Page<T> emptyPage(PageRequest request) {
            return new Page<>(List.of(), request.offset(), request.limit(), false);
        }
    }
}
