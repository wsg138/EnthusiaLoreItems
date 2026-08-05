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
        assertRetriesWithoutAnotherAccessEvent(new AbandonOnceScanner());
    }

    @Test
    void failedScanResetsAndRetriesWithoutAnotherAccessEvent() {
        assertRetriesWithoutAnotherAccessEvent(new FailOnceScanner());
    }

    private void assertRetriesWithoutAnotherAccessEvent(RetryOnceScanner scanner) {
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
        assertTrue(scanner.resetAfterFirstAttempt);
    }

    private abstract static class RetryOnceScanner extends PaperTemplateUpdateScanner {
        private int scanCalls;
        private boolean firstAttemptFinished;
        private boolean resetAfterFirstAttempt;

        @Override
        final ScanResult scan(
                Plugin plugin,
                Inventory inventory,
                Consumer<Candidate> consumer) {
            scanCalls++;
            if (scanCalls == 1) {
                try {
                    return firstAttempt();
                } finally {
                    firstAttemptFinished = true;
                }
            }
            return ScanResult.complete(0);
        }

        abstract ScanResult firstAttempt();

        @Override
        final void reset(PaperInventoryReference reference) {
            if (firstAttemptFinished) {
                resetAfterFirstAttempt = true;
            }
        }

        @Override
        final void clear() {
            // No retained cursor state in these controlled scanners.
        }
    }

    private static final class AbandonOnceScanner extends RetryOnceScanner {
        @Override
        ScanResult firstAttempt() {
            return ScanResult.abandonedScan();
        }
    }

    private static final class FailOnceScanner extends RetryOnceScanner {
        @Override
        ScanResult firstAttempt() {
            throw new IllegalStateException("controlled scan failure");
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
