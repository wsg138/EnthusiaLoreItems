package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import net.enthusia.loreitems.application.AdoptHeldItemUseCase;
import net.enthusia.loreitems.application.PrepareHeldItemAdoptionRequest;
import net.enthusia.loreitems.application.PrepareHeldItemAdoptionResult;
import net.enthusia.loreitems.application.PreparedHeldItemAdoption;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class AdoptHeldItemCommandExecutorLifecycleTest {
    private ServerMock server;
    private Plugin plugin;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        player = server.addPlayer();
        player.addAttachment(plugin, AdoptHeldItemCommandExecutor.ADOPT_PERMISSION, true);
        player.getInventory().setItemInMainHand(ItemStack.of(Material.IRON_SWORD));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void preparationFromReplacedRuntimeCannotMutateHeldItem() {
        DeferredUseCase oldUseCase = new DeferredUseCase();
        DeferredUseCase replacementUseCase = new DeferredUseCase();
        AtomicReference<AdoptHeldItemUseCase> activeUseCase =
                new AtomicReference<>(oldUseCase);
        AdoptHeldItemCommandExecutor executor = new AdoptHeldItemCommandExecutor(
                plugin,
                activeUseCase::get,
                new PaperHeldItemAdoptionOperator());
        ItemStack original = player.getInventory().getItemInMainHand().clone();

        executor.onCommand(
                player,
                command(),
                "loreitems",
                new String[] {"adopt", "restart-fence"});
        PrepareHeldItemAdoptionRequest request = oldUseCase.lastRequest;
        assertNotNull(request);

        activeUseCase.set(replacementUseCase);
        oldUseCase.preparation.complete(
                PrepareHeldItemAdoptionResult.prepared(prepared(request)));
        server.getScheduler().performOneTick();

        assertEquals(original, player.getInventory().getItemInMainHand());
        assertEquals(0, oldUseCase.completeCalls);
        assertEquals(0, oldUseCase.reviewCalls);
    }

    @Test
    void preparationFromCurrentRuntimeStillAppliesNormally() {
        DeferredUseCase useCase = new DeferredUseCase();
        AtomicReference<AdoptHeldItemUseCase> activeUseCase =
                new AtomicReference<>(useCase);
        AdoptHeldItemCommandExecutor executor = new AdoptHeldItemCommandExecutor(
                plugin,
                activeUseCase::get,
                new PaperHeldItemAdoptionOperator());

        executor.onCommand(
                player,
                command(),
                "loreitems",
                new String[] {"adopt", "current-runtime"});
        PrepareHeldItemAdoptionRequest request = useCase.lastRequest;
        assertNotNull(request);

        useCase.preparation.complete(
                PrepareHeldItemAdoptionResult.prepared(prepared(request)));
        server.getScheduler().performOneTick();

        assertEquals(1, useCase.completeCalls);
        assertEquals(0, useCase.reviewCalls);
    }

    private static PreparedHeldItemAdoption prepared(
            PrepareHeldItemAdoptionRequest request) {
        return new PreparedHeldItemAdoption(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                request.definitionKey(),
                new LoreDefinitionId(
                        UUID.fromString("22222222-2222-2222-2222-222222222222")),
                new LoreInstanceId(
                        UUID.fromString("33333333-3333-3333-3333-333333333333")),
                new TemplateRevision(1),
                request.playerId(),
                request.selectedSlot(),
                request.beforeFingerprint(),
                UUID.fromString("44444444-4444-4444-4444-444444444444"),
                1_000L,
                2_000L);
    }

    private static Command command() {
        return new Command("loreitems") {
            @Override
            public boolean execute(
                    CommandSender sender,
                    String commandLabel,
                    String[] arguments) {
                throw new AssertionError("Command dispatch should not re-enter the command object");
            }
        };
    }

    private static final class DeferredUseCase implements AdoptHeldItemUseCase {
        private final CompletableFuture<PrepareHeldItemAdoptionResult> preparation =
                new CompletableFuture<>();
        private PrepareHeldItemAdoptionRequest lastRequest;
        private int completeCalls;
        private int reviewCalls;

        @Override
        public CompletionStage<PrepareHeldItemAdoptionResult> prepare(
                PrepareHeldItemAdoptionRequest request) {
            lastRequest = request;
            return preparation;
        }

        @Override
        public CompletionStage<Boolean> complete(
                PreparedHeldItemAdoption adoption,
                String afterFingerprint) {
            completeCalls++;
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletionStage<Boolean> requireReview(
                PreparedHeldItemAdoption adoption,
                String reason) {
            reviewCalls++;
            return CompletableFuture.completedFuture(true);
        }
    }
}
