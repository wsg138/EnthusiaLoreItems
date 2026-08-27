package net.enthusia.loreitems.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
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
}
