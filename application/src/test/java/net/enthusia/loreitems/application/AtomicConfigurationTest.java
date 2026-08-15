package net.enthusia.loreitems.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AtomicConfigurationTest {
    @Test
    void replacesSharedContainerPolicyAsOneSnapshot() {
        FoundationConfiguration initial = FoundationConfiguration.defaults();
        AtomicConfiguration configuration = new AtomicConfiguration(initial);
        FoundationConfiguration replacement = new FoundationConfiguration(
                initial.databaseBusyTimeoutMillis(),
                initial.databaseQueueCapacity(),
                initial.databaseShutdownTimeoutSeconds(),
                initial.deliveryClaimBatchSize(),
                initial.deliveryClaimLeaseSeconds(),
                initial.duplicateWarningIntervalSeconds(),
                initial.defaultPageSize(),
                initial.maxPageSize(),
                initial.mutationBudgetPerTick(),
                !initial.sharedContainersAllowed());

        AtomicConfiguration.ReloadResult result = configuration.replace(replacement);

        assertTrue(result.applied());
        assertEquals(replacement, configuration.current());
    }

    @Test
    void rejectsEveryConstructionCapturedSettingWithoutMutatingCurrentSnapshot() {
        FoundationConfiguration initial = FoundationConfiguration.defaults();
        assertRestartRequired(initial, new FoundationConfiguration(
                initial.databaseBusyTimeoutMillis() + 1,
                initial.databaseQueueCapacity(),
                initial.databaseShutdownTimeoutSeconds(),
                initial.deliveryClaimBatchSize(),
                initial.deliveryClaimLeaseSeconds(),
                initial.duplicateWarningIntervalSeconds(),
                initial.defaultPageSize(),
                initial.maxPageSize(),
                initial.mutationBudgetPerTick(),
                initial.sharedContainersAllowed()));
        assertRestartRequired(initial, new FoundationConfiguration(
                initial.databaseBusyTimeoutMillis(),
                initial.databaseQueueCapacity() + 1,
                initial.databaseShutdownTimeoutSeconds(),
                initial.deliveryClaimBatchSize(),
                initial.deliveryClaimLeaseSeconds(),
                initial.duplicateWarningIntervalSeconds(),
                initial.defaultPageSize(),
                initial.maxPageSize(),
                initial.mutationBudgetPerTick(),
                initial.sharedContainersAllowed()));
        assertRestartRequired(initial, new FoundationConfiguration(
                initial.databaseBusyTimeoutMillis(),
                initial.databaseQueueCapacity(),
                initial.databaseShutdownTimeoutSeconds() + 1,
                initial.deliveryClaimBatchSize(),
                initial.deliveryClaimLeaseSeconds(),
                initial.duplicateWarningIntervalSeconds(),
                initial.defaultPageSize(),
                initial.maxPageSize(),
                initial.mutationBudgetPerTick(),
                initial.sharedContainersAllowed()));
        assertRestartRequired(initial, new FoundationConfiguration(
                initial.databaseBusyTimeoutMillis(),
                initial.databaseQueueCapacity(),
                initial.databaseShutdownTimeoutSeconds(),
                initial.deliveryClaimBatchSize() + 1,
                initial.deliveryClaimLeaseSeconds(),
                initial.duplicateWarningIntervalSeconds(),
                initial.defaultPageSize(),
                initial.maxPageSize(),
                initial.mutationBudgetPerTick(),
                initial.sharedContainersAllowed()));
        assertRestartRequired(initial, new FoundationConfiguration(
                initial.databaseBusyTimeoutMillis(),
                initial.databaseQueueCapacity(),
                initial.databaseShutdownTimeoutSeconds(),
                initial.deliveryClaimBatchSize(),
                initial.deliveryClaimLeaseSeconds() + 1,
                initial.duplicateWarningIntervalSeconds(),
                initial.defaultPageSize(),
                initial.maxPageSize(),
                initial.mutationBudgetPerTick(),
                initial.sharedContainersAllowed()));
        assertRestartRequired(initial, new FoundationConfiguration(
                initial.databaseBusyTimeoutMillis(),
                initial.databaseQueueCapacity(),
                initial.databaseShutdownTimeoutSeconds(),
                initial.deliveryClaimBatchSize(),
                initial.deliveryClaimLeaseSeconds(),
                initial.duplicateWarningIntervalSeconds() + 1,
                initial.defaultPageSize(),
                initial.maxPageSize(),
                initial.mutationBudgetPerTick(),
                initial.sharedContainersAllowed()));
        assertRestartRequired(initial, new FoundationConfiguration(
                initial.databaseBusyTimeoutMillis(),
                initial.databaseQueueCapacity(),
                initial.databaseShutdownTimeoutSeconds(),
                initial.deliveryClaimBatchSize(),
                initial.deliveryClaimLeaseSeconds(),
                initial.duplicateWarningIntervalSeconds(),
                initial.defaultPageSize() - 1,
                initial.maxPageSize(),
                initial.mutationBudgetPerTick(),
                initial.sharedContainersAllowed()));
        assertRestartRequired(initial, new FoundationConfiguration(
                initial.databaseBusyTimeoutMillis(),
                initial.databaseQueueCapacity(),
                initial.databaseShutdownTimeoutSeconds(),
                initial.deliveryClaimBatchSize(),
                initial.deliveryClaimLeaseSeconds(),
                initial.duplicateWarningIntervalSeconds(),
                initial.defaultPageSize(),
                initial.maxPageSize() - 1,
                initial.mutationBudgetPerTick(),
                initial.sharedContainersAllowed()));
        assertRestartRequired(initial, new FoundationConfiguration(
                initial.databaseBusyTimeoutMillis(),
                initial.databaseQueueCapacity(),
                initial.databaseShutdownTimeoutSeconds(),
                initial.deliveryClaimBatchSize(),
                initial.deliveryClaimLeaseSeconds(),
                initial.duplicateWarningIntervalSeconds(),
                initial.defaultPageSize(),
                initial.maxPageSize(),
                initial.mutationBudgetPerTick() + 1,
                initial.sharedContainersAllowed()));
    }

    private static void assertRestartRequired(
            FoundationConfiguration initial,
            FoundationConfiguration incompatible) {
        AtomicConfiguration configuration = new AtomicConfiguration(initial);

        AtomicConfiguration.ReloadResult result = configuration.replace(incompatible);

        assertFalse(result.applied());
        assertTrue(result.detail().contains("require a restart"));
        assertEquals(initial, configuration.current());
    }

    @Test
    void rejectsUnsafeBoundsBeforeTheyCanBePublished() {
        assertThrows(IllegalArgumentException.class, () -> new FoundationConfiguration(
                5_000, 0, 10, 32, 30, 300, 45, 100, 16, false));
    }
}
