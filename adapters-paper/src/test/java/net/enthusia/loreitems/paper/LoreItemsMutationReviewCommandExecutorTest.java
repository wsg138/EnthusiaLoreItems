package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.enthusia.loreitems.application.PendingMutationReviewUseCase;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class LoreItemsMutationReviewCommandExecutorTest {
    private static final UUID MUTATION_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private ServerMock server;
    private Plugin plugin;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        player = server.addPlayer();
        player.addAttachment(plugin, LoreItemsMutationReviewCommandExecutor.REVIEW_PERMISSION, true);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void retrySubmitsEvidenceAndWakesAccessibleMutationWork() {
        AtomicReference<PendingMutationReviewUseCase.Request> captured = new AtomicReference<>();
        AtomicInteger wakes = new AtomicInteger();
        PendingMutationReviewUseCase useCase = request -> {
            captured.set(request);
            return CompletableFuture.completedFuture(new PendingMutationReviewUseCase.Result(
                    PendingMutationReviewUseCase.Status.RETRIED,
                    "retry accepted"));
        };
        LoreItemsMutationReviewCommandExecutor executor =
                new LoreItemsMutationReviewCommandExecutor(plugin, () -> useCase, wakes::incrementAndGet);

        executor.onCommand(
                player,
                command(),
                "loreitemsreview",
                new String[] {
                    MUTATION_ID.toString(),
                    "template_update",
                    "retry",
                    "physical",
                    "evidence",
                    "reviewed"
                });
        server.getScheduler().performOneTick();

        PendingMutationReviewUseCase.Request request = captured.get();
        assertNotNull(request);
        assertEquals(MUTATION_ID, request.mutationId());
        assertEquals("template_update", request.expectedMutationType());
        assertEquals(PendingMutationReviewUseCase.Resolution.RETRY, request.resolution());
        assertEquals("physical evidence reviewed", request.evidenceDetail());
        assertEquals(1, wakes.get());
    }

    @Test
    void cancelDoesNotWakeMutationWorker() {
        AtomicInteger wakes = new AtomicInteger();
        PendingMutationReviewUseCase useCase = request -> CompletableFuture.completedFuture(
                new PendingMutationReviewUseCase.Result(
                        PendingMutationReviewUseCase.Status.CANCELLED,
                        "cancel accepted"));
        LoreItemsMutationReviewCommandExecutor executor =
                new LoreItemsMutationReviewCommandExecutor(plugin, () -> useCase, wakes::incrementAndGet);

        executor.onCommand(
                player,
                command(),
                "loreitemsreview",
                new String[] {
                    MUTATION_ID.toString(),
                    "template_update",
                    "cancel",
                    "already",
                    "applied"
                });
        server.getScheduler().performOneTick();

        assertEquals(0, wakes.get());
    }

    private static Command command() {
        return new Command("loreitemsreview") {
            @Override
            public boolean execute(CommandSender sender, String commandLabel, String[] args) {
                return false;
            }
        };
    }
}
