package net.enthusia.loreitems.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AtomicConfigurationTest {
    @Test
    void replacesReloadableSettingsAsOneSnapshot() {
        FoundationConfiguration initial = FoundationConfiguration.defaults();
        AtomicConfiguration configuration = new AtomicConfiguration(initial);
        FoundationConfiguration replacement = new FoundationConfiguration(
                initial.databaseBusyTimeoutMillis(),
                initial.databaseQueueCapacity(),
                initial.databaseShutdownTimeoutSeconds(),
                12,
                45,
                600,
                25,
                80,
                8,
                true);

        AtomicConfiguration.ReloadResult result = configuration.replace(replacement);

        assertTrue(result.applied());
        assertEquals(replacement, configuration.current());
    }

    @Test
    void rejectsStartupResourceChangesWithoutMutatingCurrentSnapshot() {
        FoundationConfiguration initial = FoundationConfiguration.defaults();
        AtomicConfiguration configuration = new AtomicConfiguration(initial);
        FoundationConfiguration incompatible = new FoundationConfiguration(
                initial.databaseBusyTimeoutMillis(),
                initial.databaseQueueCapacity() + 1,
                initial.databaseShutdownTimeoutSeconds(),
                initial.deliveryClaimBatchSize(),
                initial.deliveryClaimLeaseSeconds(),
                initial.duplicateWarningIntervalSeconds(),
                initial.defaultPageSize(),
                initial.maxPageSize(),
                initial.mutationBudgetPerTick(),
                initial.sharedContainersAllowed());

        AtomicConfiguration.ReloadResult result = configuration.replace(incompatible);

        assertFalse(result.applied());
        assertEquals(initial, configuration.current());
    }

    @Test
    void rejectsUnsafeBoundsBeforeTheyCanBePublished() {
        assertThrows(IllegalArgumentException.class, () -> new FoundationConfiguration(
                5_000, 0, 10, 32, 30, 300, 45, 100, 16, false));
    }
}
