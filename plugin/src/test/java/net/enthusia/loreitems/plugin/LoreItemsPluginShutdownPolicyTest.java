package net.enthusia.loreitems.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class LoreItemsPluginShutdownPolicyTest {
    @Test
    void trackingDrainNeverUsesLessTimeThanConfiguredDatabaseDrain() {
        assertEquals(
                Duration.ofSeconds(10),
                LoreItemsPlugin.trackingShutdownTimeout(Duration.ofSeconds(10)));
        assertEquals(
                Duration.ofSeconds(60),
                LoreItemsPlugin.trackingShutdownTimeout(Duration.ofSeconds(60)));
    }

    @Test
    void trackingDrainRetainsFiveSecondMinimumForShortDatabaseDrain() {
        assertEquals(
                Duration.ofSeconds(5),
                LoreItemsPlugin.trackingShutdownTimeout(Duration.ofSeconds(1)));
        assertEquals(
                Duration.ofSeconds(5),
                LoreItemsPlugin.trackingShutdownTimeout(Duration.ofSeconds(5)));
    }

    @Test
    void sameInstanceReuseRequiresSuccessfulTrackingQuiescence() {
        CompletableFuture<Void> pending = new CompletableFuture<>();
        assertFalse(LoreItemsPlugin.trackingQuiesced(pending));

        CompletableFuture<Void> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("tracking failed"));
        assertFalse(LoreItemsPlugin.trackingQuiesced(failed));

        CompletableFuture<Void> cancelled = new CompletableFuture<>();
        cancelled.cancel(false);
        assertFalse(LoreItemsPlugin.trackingQuiesced(cancelled));

        assertTrue(LoreItemsPlugin.trackingQuiesced(CompletableFuture.completedFuture(null)));
    }
}
