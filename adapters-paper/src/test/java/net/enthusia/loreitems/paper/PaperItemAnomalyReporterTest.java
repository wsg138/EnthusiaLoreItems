package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.AnomalyWarningSink;
import net.enthusia.loreitems.application.ItemAnomalyObservationUseCase;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.domain.LocationDescriptor;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class PaperItemAnomalyReporterTest {
    private static final String FIRST_SUFFIX = "first";
    private static final String FIRST_A_SUFFIX = "first-a";
    private static final String TEST_SOURCE = "test";
    private static final String FIRST_DUPLICATE_DETAIL = "first duplicate";
    private static final int FIRST_CALL_COUNT = 1;
    private static final LoreItemIdentity FIRST_IDENTITY = identity(
            "11111111-1111-1111-1111-111111111111");
    private static final LoreItemIdentity SECOND_IDENTITY = identity(
            "22222222-2222-2222-2222-222222222222");

    private ServerMock server;
    private Plugin plugin;
    private PaperItemAnomalyReporter reporter;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
    }

    @AfterEach
    void tearDown() {
        if (reporter != null) {
            reporter.close();
        }
        MockBukkit.unmock();
    }

    @Test
    void queuesDistinctEvidenceWhilePersistenceCapacityIsOccupied() {
        RecordingUseCase useCase = new RecordingUseCase();
        register(useCase);
        reporter = new PaperItemAnomalyReporter(plugin, 1);

        reporter.recordDuplicate(
                FIRST_IDENTITY,
                conflictLocation(FIRST_SUFFIX),
                List.of(playerLocation(FIRST_A_SUFFIX), playerLocation("first-b")),
                TEST_SOURCE,
                FIRST_DUPLICATE_DETAIL);
        reporter.recordDuplicate(
                SECOND_IDENTITY,
                conflictLocation("second"),
                List.of(playerLocation("second-a"), playerLocation("second-b")),
                TEST_SOURCE,
                "second duplicate");

        assertEquals(1, useCase.callCount);
        useCase.firstResult.complete(recorded());
        assertEquals(2, useCase.callCount);
    }

    @Test
    void coalescesRepeatedPendingEvidenceForTheSameLocation() {
        RecordingUseCase useCase = new RecordingUseCase();
        register(useCase);
        reporter = new PaperItemAnomalyReporter(plugin, 1);

        reporter.recordDuplicate(
                FIRST_IDENTITY,
                conflictLocation(FIRST_SUFFIX),
                List.of(playerLocation(FIRST_A_SUFFIX), playerLocation("first-b")),
                TEST_SOURCE,
                FIRST_DUPLICATE_DETAIL);
        reporter.recordDuplicate(
                SECOND_IDENTITY,
                conflictLocation("second"),
                List.of(playerLocation("second-a"), playerLocation("second-b")),
                TEST_SOURCE,
                "older detail");
        reporter.recordDuplicate(
                SECOND_IDENTITY,
                conflictLocation("second"),
                List.of(playerLocation("second-c"), playerLocation("second-d")),
                TEST_SOURCE,
                "newest detail");

        useCase.firstResult.complete(recorded());

        assertEquals(2, useCase.callCount);
        assertEquals("newest detail", useCase.lastRequest.detail());
    }

    @Test
    void ignoresRepeatedEvidenceDuringThePostCompletionCooldown() {
        RecordingUseCase useCase = new RecordingUseCase();
        register(useCase);
        reporter = new PaperItemAnomalyReporter(plugin, 1);

        LocationDescriptor conflict = conflictLocation("same");
        List<LocationDescriptor> evidence =
                List.of(playerLocation("same-a"), playerLocation("same-b"));
        reporter.recordDuplicate(
                FIRST_IDENTITY,
                conflict,
                evidence,
                TEST_SOURCE,
                "first observation");
        useCase.firstResult.complete(recorded());

        reporter.recordDuplicate(
                FIRST_IDENTITY,
                conflict,
                evidence,
                TEST_SOURCE,
                "immediate repeat");

        assertEquals(1, useCase.callCount);
    }

    @Test
    void onlyNewActiveAnomaliesRequestAnImmediateStaffWarning() {
        RecordingUseCase useCase = new RecordingUseCase();
        RecordingWarningSink warningSink = new RecordingWarningSink();
        register(useCase);
        register(warningSink);
        reporter = new PaperItemAnomalyReporter(plugin, 1);

        reporter.recordDuplicate(
                FIRST_IDENTITY,
                conflictLocation(FIRST_SUFFIX),
                List.of(playerLocation(FIRST_A_SUFFIX), playerLocation("first-b")),
                TEST_SOURCE,
                FIRST_DUPLICATE_DETAIL);
        useCase.firstResult.complete(ItemAnomalyObservationUseCase.Result.of(
                ItemAnomalyObservationUseCase.Status.REFRESHED,
                "refreshed"));

        assertEquals(0, warningSink.requestCount);
    }

    @Test
    void newlyRecordedAnomalyRequestsAnImmediateStaffWarning() {
        RecordingUseCase useCase = new RecordingUseCase();
        RecordingWarningSink warningSink = new RecordingWarningSink();
        register(useCase);
        register(warningSink);
        reporter = new PaperItemAnomalyReporter(plugin, 1);

        reporter.recordDuplicate(
                FIRST_IDENTITY,
                conflictLocation(FIRST_SUFFIX),
                List.of(playerLocation(FIRST_A_SUFFIX), playerLocation("first-b")),
                TEST_SOURCE,
                FIRST_DUPLICATE_DETAIL);
        useCase.firstResult.complete(recorded());

        assertEquals(1, warningSink.requestCount);
    }

    private void register(ItemAnomalyObservationUseCase useCase) {
        server.getServicesManager().register(
                ItemAnomalyObservationUseCase.class,
                useCase,
                plugin,
                ServicePriority.Normal);
    }

    private void register(AnomalyWarningSink warningSink) {
        server.getServicesManager().register(
                AnomalyWarningSink.class,
                warningSink,
                plugin,
                ServicePriority.Normal);
    }

    private static ItemAnomalyObservationUseCase.Result recorded() {
        return ItemAnomalyObservationUseCase.Result.of(
                ItemAnomalyObservationUseCase.Status.RECORDED,
                "recorded");
    }

    private static LocationDescriptor conflictLocation(String suffix) {
        return new LocationDescriptor(
                LocationDescriptor.Type.DUPLICATE_CONFLICT,
                "conflict:" + suffix,
                "pair:" + suffix);
    }

    private static LocationDescriptor playerLocation(String suffix) {
        return new LocationDescriptor(
                LocationDescriptor.Type.PLAYER_INVENTORY,
                "player:" + suffix,
                "slot:0");
    }

    private static LoreItemIdentity identity(String instanceId) {
        return new LoreItemIdentity(
                new LoreDefinitionId(UUID.fromString(
                        "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")),
                new LoreInstanceId(UUID.fromString(instanceId)),
                new TemplateRevision(1));
    }

    private static final class RecordingUseCase implements ItemAnomalyObservationUseCase {
        private final CompletableFuture<Result> firstResult = new CompletableFuture<>();
        private int callCount;
        private Request lastRequest;

        @Override
        public CompletionStage<Result> record(Request request) {
            callCount++;
            lastRequest = request;
            return callCount == FIRST_CALL_COUNT
                    ? firstResult
                    : CompletableFuture.completedFuture(recorded());
        }
    }

    private static final class RecordingWarningSink implements AnomalyWarningSink {
        private int requestCount;

        @Override
        public void requestWarning() {
            requestCount++;
        }
    }
}
