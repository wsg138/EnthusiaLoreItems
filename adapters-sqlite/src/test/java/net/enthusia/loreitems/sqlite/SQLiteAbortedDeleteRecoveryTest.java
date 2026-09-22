package net.enthusia.loreitems.sqlite;

import static net.enthusia.loreitems.sqlite.SQLiteDestructiveTestFixture.FINGERPRINT;
import static net.enthusia.loreitems.sqlite.SQLiteDestructiveTestFixture.LOCATION_TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.ReviewRequest;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.ReviewResolution;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.ReviewStatus;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.StartRequest;
import net.enthusia.loreitems.application.DestructiveRemovalExecutionUseCase;
import net.enthusia.loreitems.application.DestructiveRemovalExecutionUseCase.Observation;
import net.enthusia.loreitems.application.DestructiveRemovalExecutionUseCase.Status;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.domain.DestructiveEffectState;
import net.enthusia.loreitems.domain.DestructiveOperationState;
import net.enthusia.loreitems.domain.DestructiveOperationType;
import net.enthusia.loreitems.domain.DestructiveTargetState;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SQLiteAbortedDeleteRecoveryTest {
    private static final Instant NOW = Instant.ofEpochMilli(2_000L);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String ADMIN = "admin";
    private static final String REMOVED_LIFECYCLE = "REMOVED";

    @TempDir
    Path temporaryDirectory;

    @Test
    void lateCopyReopensKnownTargetAfterEvidenceAbortedFullDelete() {
        try (SQLiteDestructiveTestFixture fixture = fixture("aborted-known-delete.db")) {
            AbortedDelete context = abortFullDelete(fixture);

            var prepared = context.execution().prepare(
                            fixture.observation(context.seed(), SQLiteDestructiveTestFixture.LOCATION_KEY))
                    .toCompletableFuture().join();
            assertEquals(Status.PREPARED, prepared.status());
            assertEquals(DestructiveOperationState.ACTIVE, operation(context).state());
            assertTrue(context.execution().complete(prepared.preparedRemoval(), FINGERPRINT)
                    .toCompletableFuture().join());

            assertEquals(DestructiveTargetState.COMPLETED,
                    target(context, context.seed().instanceId()).state());
            assertEquals(DestructiveOperationState.COMPLETED, operation(context).state());
            assertEquals(REMOVED_LIFECYCLE, fixture.instanceLifecycle(context.seed().instanceId()));
        }
    }

    @Test
    void abortedDeleteMarkerKeepsCreatingTargetsForNovelBackupCopies() {
        try (SQLiteDestructiveTestFixture fixture = fixture("aborted-novel-delete.db")) {
            AbortedDelete context = abortFullDelete(fixture);
            LoreInstanceId firstId = new LoreInstanceId(UUID.randomUUID());
            completeLateCopy(fixture, context, firstId, "player:late-one");

            assertEquals(DestructiveOperationState.ABORTED, operation(context).state());
            assertEquals(REMOVED_LIFECYCLE, fixture.instanceLifecycle(firstId));

            LoreInstanceId secondId = new LoreInstanceId(UUID.randomUUID());
            completeLateCopy(fixture, context, secondId, "player:late-two");
            assertEquals(REMOVED_LIFECYCLE, fixture.instanceLifecycle(secondId));
            assertEquals(3L, operation(context).targetCount());
            assertEquals(DestructiveOperationState.ABORTED, operation(context).state());
        }
    }

    private AbortedDelete abortFullDelete(SQLiteDestructiveTestFixture fixture) {
        var seed = fixture.seed(true);
        var administration = fixture.administration();
        var preview = fixture.preview(DestructiveOperationType.DELETE_DEFINITION, seed, null);
        var started = administration.start(new StartRequest(preview, ADMIN, "abort-full-delete"))
                .toCompletableFuture().join();
        DestructiveRemovalExecutionUseCase execution = fixture.execution(30L);
        var prepared = execution.prepare(
                        fixture.observation(seed, SQLiteDestructiveTestFixture.LOCATION_KEY))
                .toCompletableFuture().join();
        assertEquals(Status.PREPARED, prepared.status());
        assertTrue(execution.requireReview(
                        prepared.preparedRemoval(),
                        DestructiveEffectState.NONE_OBSERVED,
                        FINGERPRINT,
                        null,
                        "Physical inspection proved that no removal side effect occurred.")
                .toCompletableFuture().join());
        var resolved = administration.resolveReview(new ReviewRequest(
                        started.operation().operationId(),
                        seed.instanceId(),
                        ReviewResolution.ABORT_NO_SIDE_EFFECT,
                        ADMIN,
                        "The original item remained present and unchanged."))
                .toCompletableFuture().join();
        assertEquals(ReviewStatus.RESOLVED, resolved.status());
        assertEquals(DestructiveTargetState.ABORTED, resolved.target().state());
        assertEquals(DestructiveOperationState.ABORTED,
                administration.listOperations(PageRequest.first(10))
                        .toCompletableFuture().join().items().getFirst().state());
        return new AbortedDelete(seed, administration, execution, started.operation().operationId());
    }

    private static void completeLateCopy(
            SQLiteDestructiveTestFixture fixture,
            AbortedDelete context,
            LoreInstanceId instanceId,
            String locationKey) {
        var prepared = context.execution().prepare(
                        lateObservation(context, instanceId, locationKey))
                .toCompletableFuture().join();
        assertEquals(Status.PREPARED, prepared.status());
        assertTrue(context.execution().complete(prepared.preparedRemoval(), FINGERPRINT)
                .toCompletableFuture().join());
        assertEquals(DestructiveTargetState.COMPLETED,
                target(context, instanceId).state());
    }

    private static Observation lateObservation(
            AbortedDelete context,
            LoreInstanceId instanceId,
            String locationKey) {
        return new Observation(
                new LoreItemIdentity(
                        context.seed().definitionId(),
                        instanceId,
                        new TemplateRevision(1L)),
                LOCATION_TYPE,
                locationKey,
                null,
                FINGERPRINT);
    }

    private static DestructiveAdministrationUseCase.OperationView operation(AbortedDelete context) {
        return context.administration().listOperations(PageRequest.first(10))
                .toCompletableFuture().join().items().getFirst();
    }

    private static DestructiveAdministrationUseCase.TargetView target(
            AbortedDelete context,
            LoreInstanceId instanceId) {
        return context.administration().listTargets(context.operationId(), PageRequest.first(20))
                .toCompletableFuture().join().items().stream()
                .filter(candidate -> candidate.instanceId().equals(instanceId))
                .findFirst()
                .orElseThrow();
    }

    private SQLiteDestructiveTestFixture fixture(String fileName) {
        return new SQLiteDestructiveTestFixture(temporaryDirectory, fileName, CLOCK);
    }

    private record AbortedDelete(
            SQLiteDestructiveTestFixture.Seed seed,
            DestructiveAdministrationUseCase administration,
            DestructiveRemovalExecutionUseCase execution,
            UUID operationId) {}
}
