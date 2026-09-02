package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.AuditEventRecord;
import net.enthusia.loreitems.application.ItemAnomalyObservationUseCase;
import net.enthusia.loreitems.application.LoreItemsAdministrationUseCase;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import net.enthusia.loreitems.domain.InstanceAnomaly;
import net.enthusia.loreitems.domain.InstanceCurrentState;
import net.enthusia.loreitems.domain.InstanceObservation;
import net.enthusia.loreitems.domain.LoreInstanceId;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.plugin.ServicePriority;
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
    void coalescesAnInFlightWakeupIntoOneFollowUpQuery() {
        RecordingAdministrationUseCase useCase = new RecordingAdministrationUseCase();
        worker = new PaperAnomalyWarningWorker(plugin, useCase, 300, 10);

        worker.requestWarning();
        worker.requestWarning();
        assertEquals(1, useCase.warningQueryCount);

        useCase.firstQuery.complete(emptyPage());

        assertEquals(2, useCase.warningQueryCount);
    }

    @Test
    void replacesBootstrapTrackingPairInsteadOfLeavingDuplicateListenersActive() {
        TrackingAndAnomalyUseCase observationUseCase = new TrackingAndAnomalyUseCase();
        plugin.getServer().getServicesManager().register(
                ItemAnomalyObservationUseCase.class,
                observationUseCase,
                plugin,
                ServicePriority.Normal);
        PaperUniqueAccessTrackingListener bootstrapUnique =
                new PaperUniqueAccessTrackingListener(
                        plugin,
                        () -> observationUseCase,
                        () -> 4,
                        MetricsPort.noOp());
        PaperPhysicalTrackingListener bootstrapPhysical =
                new PaperPhysicalTrackingListener(
                        plugin,
                        () -> observationUseCase,
                        () -> 4,
                        MetricsPort.noOp());
        bootstrapUnique.start();
        bootstrapPhysical.start();
        assertEquals(1L, listenerCount(PaperUniqueAccessTrackingListener.class));
        assertEquals(1L, listenerCount(PaperPhysicalTrackingListener.class));

        worker = new PaperAnomalyWarningWorker(
                plugin,
                new RecordingAdministrationUseCase(),
                300,
                10);
        worker.start();

        assertEquals(1L, listenerCount(PaperUniqueAccessTrackingListener.class));
        assertEquals(1L, listenerCount(PaperPhysicalTrackingListener.class));
        bootstrapPhysical.close();
        bootstrapUnique.close();
    }

    @Test
    void rejectsANonPositiveTrackingBudget() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PaperAnomalyWarningWorker(
                        plugin,
                        new RecordingAdministrationUseCase(),
                        300,
                        10,
                        0));
    }

    private long listenerCount(Class<?> type) {
        return HandlerList.getRegisteredListeners(plugin).stream()
                .map(RegisteredListener::getListener)
                .filter(type::isInstance)
                .count();
    }

    private static Page<InstanceAnomaly> emptyPage() {
        return new Page<>(List.of(), 0, 10, false);
    }

    private static final class TrackingAndAnomalyUseCase
            implements ItemAnomalyObservationUseCase, TrackingObservationUseCase {
        @Override
        public CompletionStage<ItemAnomalyObservationUseCase.Result> record(
                ItemAnomalyObservationUseCase.Request request) {
            return CompletableFuture.completedFuture(ItemAnomalyObservationUseCase.Result.of(
                    ItemAnomalyObservationUseCase.Status.RECORDED,
                    "Recorded anomaly."));
        }

        @Override
        public CompletionStage<TrackingObservationUseCase.Result> record(
                TrackingObservationUseCase.Request request) {
            return CompletableFuture.completedFuture(TrackingObservationUseCase.Result.of(
                    TrackingObservationUseCase.Status.RECORDED,
                    "Recorded tracking observation."));
        }
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
