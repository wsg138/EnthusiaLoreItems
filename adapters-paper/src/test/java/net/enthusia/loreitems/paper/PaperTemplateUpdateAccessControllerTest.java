package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.PreparedTemplateUpdate;
import net.enthusia.loreitems.application.TemplateUpdateExecutionUseCase;
import net.enthusia.loreitems.application.TemplateUpdatePrepareResult;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class PaperTemplateUpdateAccessControllerTest {
    private ServerMock server;
    private PlayerMock player;
    private Plugin plugin;
    private PaperTemplateUpdateAccessController controller;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer();
        plugin = MockBukkit.createMockPlugin();
    }

    @AfterEach
    void tearDown() {
        if (controller != null) {
            controller.close();
        }
        MockBukkit.unmock();
    }

    @Test
    void abandonedScanResetsAndRetriesWithoutAnotherAccessEvent() {
        AbandonOnceScanner scanner = new AbandonOnceScanner();
        controller = new PaperTemplateUpdateAccessController(
                plugin,
                new UnexpectedExecutionUseCase(),
                new PaperTemplateUpdateOperator(),
                1,
                scanner);
        PaperInventoryReference reference =
                new PaperInventoryReference.PlayerMain(player.getUniqueId());

        controller.enqueue(reference);
        controller.drain();
        assertEquals(1, scanner.scanCalls);

        controller.drain();
        assertEquals(2, scanner.scanCalls);
        assertTrue(scanner.resetAfterAbandon);
    }

    private static final class AbandonOnceScanner extends PaperTemplateUpdateScanner {
        private int scanCalls;
        private boolean abandoned;
        private boolean resetAfterAbandon;

        @Override
        ScanResult scan(
                Plugin plugin,
                Inventory inventory,
                Consumer<Candidate> consumer) {
            scanCalls++;
            if (!abandoned) {
                abandoned = true;
                return ScanResult.abandonedScan();
            }
            return ScanResult.complete(0);
        }

        @Override
        void reset(PaperInventoryReference reference) {
            if (abandoned) {
                resetAfterAbandon = true;
            }
        }

        @Override
        void clear() {
            // No retained cursor state in this controlled scanner.
        }
    }

    private static final class UnexpectedExecutionUseCase
            implements TemplateUpdateExecutionUseCase {
        @Override
        public CompletionStage<TemplateUpdatePrepareResult> prepare(
                LoreItemIdentity observedIdentity) {
            return unexpected();
        }

        @Override
        public CompletionStage<Boolean> release(
                PreparedTemplateUpdate update,
                String reason) {
            return unexpected();
        }

        @Override
        public CompletionStage<Boolean> complete(
                PreparedTemplateUpdate update,
                String beforeFingerprint,
                String afterFingerprint) {
            return unexpected();
        }

        @Override
        public CompletionStage<Boolean> requireReview(
                PreparedTemplateUpdate update,
                String reason,
                String beforeFingerprint,
                String afterFingerprint) {
            return unexpected();
        }

        private static <T> CompletionStage<T> unexpected() {
            return CompletableFuture.failedFuture(
                    new AssertionError("No template update should be dispatched"));
        }
    }
}
