package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.AuditEventRecord;
import net.enthusia.loreitems.application.LoreItemsAdministrationUseCase;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.domain.InstanceAnomaly;
import net.enthusia.loreitems.domain.InstanceCurrentState;
import net.enthusia.loreitems.domain.InstanceObservation;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class PaperAnomalyWarningWorkerTest {
    private Plugin plugin;
    private PaperAnomalyWarningWorker worker;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
    }

    @AfterEach
    void tearDown() {
        if (worker != null) {
            worker.close();
        }
        MockBukkit.unmock();
    }

    @Test
    void coalescesAnInFlightWakeupIntoOneFollowUpQueryWhenNothingWasFound() {
        RecordingAdministrationUseCase useCase = new RecordingAdministrationUseCase();
        worker = new PaperAnomalyWarningWorker(plugin, useCase, 300, 10);

        worker.requestWarning();
        worker.requestWarning();
        assertEquals(1, useCase.warningQueryCount);

        useCase.firstQuery.complete(emptyPage());

        assertEquals(2, useCase.warningQueryCount);
    }

    @Test
    void successfulWarningSuppressesRepeatedWakeupsUntilTheIntervalExpires() {
        RecordingAdministrationUseCase useCase = new RecordingAdministrationUseCase();
        worker = new PaperAnomalyWarningWorker(plugin, useCase, 300, 10);

        worker.requestWarning();
        worker.requestWarning();
        useCase.firstQuery.complete(anomalyPage());
        worker.requestWarning();

        assertEquals(1, useCase.warningQueryCount);
    }

    private static Page<InstanceAnomaly> emptyPage() {
        return new Page<>(List.of(), 0, 10, false);
    }

    private static Page<InstanceAnomaly> anomalyPage() {
        InstanceAnomaly anomaly = new InstanceAnomaly(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                new LoreInstanceId(UUID.fromString(
                        "22222222-2222-2222-2222-222222222222")),
                new LoreDefinitionId(UUID.fromString(
                        "33333333-3333-3333-3333-333333333333")),
                InstanceAnomaly.Type.DUPLICATE_INSTANCE,
                InstanceAnomaly.Status.OPEN,
                "duplicate evidence",
                1_000L,
                1_000L,
                null,
                null,
                null,
                null,
                0L);
        return new Page<>(List.of(anomaly), 0, 10, false);
    }

    private static final class RecordingAdministrationUseCase
            implements LoreItemsAdministrationUseCase {
        private final CompletableFuture<Page<InstanceAnomaly>> firstQuery =
                new CompletableFuture<>();
        private int warningQueryCount;

        @Override
        public CompletionStage<Page<InstanceAnomaly>> listActiveAnomalies(
                PageRequest request) {
            return unsupported();
        }

        @Override
        public CompletionStage<Page<InstanceAnomaly>> listWarningAnomalies(
                PageRequest request) {
            warningQueryCount++;
            return warningQueryCount == 1
                    ? firstQuery
                    : CompletableFuture.completedFuture(emptyPage());
        }

        @Override
        public CompletionStage<Optional<InstanceCurrentState>> findCurrentState(
                LoreInstanceId instanceId) {
            return unsupported();
        }

        @Override
        public CompletionStage<Page<InstanceObservation>> listInstanceObservations(
                LoreInstanceId instanceId,
                PageRequest request) {
            return unsupported();
        }

        @Override
        public CompletionStage<Page<InstanceAnomaly>> listInstanceAnomalies(
                LoreInstanceId instanceId,
                PageRequest request) {
            return unsupported();
        }

        @Override
        public CompletionStage<Page<AuditEventRecord>> listInstanceAudit(
                LoreInstanceId instanceId,
                PageRequest request) {
            return unsupported();
        }

        @Override
        public CompletionStage<RecoveryPage> listRecovery(PageRequest request) {
            return unsupported();
        }

        private static <T> CompletionStage<T> unsupported() {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }
    }
}
