package net.enthusia.loreitems.sqlite;

import static net.enthusia.loreitems.sqlite.SQLiteDestructiveTestFixture.FINGERPRINT;
import static net.enthusia.loreitems.sqlite.SQLiteDestructiveTestFixture.LOCATION_KEY;
import static net.enthusia.loreitems.sqlite.SQLiteDestructiveTestFixture.LOCATION_TYPE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.ControlRequest;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.ReviewRequest;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.ReviewResolution;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.ReviewStatus;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.StartRequest;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.StartStatus;
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

class SQLiteDestructiveOperationStoreTest {
    private static final Instant NOW = Instant.ofEpochMilli(2_000L);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String ADMIN_ACTOR = "admin";

    @TempDir
    Path temporaryDirectory;

    @Test
    void fullDeleteCommitsMarkerSnapshotAndIdempotentAcceptance() {
        try (SQLiteDestructiveTestFixture fixture = fixture("delete.db")) {
            var seed = fixture.seed(true);
            var administration = fixture.administration();
            var preview = fixture.preview(
                    DestructiveOperationType.DELETE_DEFINITION, seed, null);

            var started = administration.start(new StartRequest(
                            preview, ADMIN_ACTOR, "delete-request-1"))
                    .toCompletableFuture().join();
            var repeated = administration.start(new StartRequest(
                            preview, ADMIN_ACTOR, "delete-request-1"))
                    .toCompletableFuture().join();

            assertEquals(StartStatus.STARTED, started.status());
            assertEquals(StartStatus.ALREADY_ACCEPTED, repeated.status());
            assertEquals(started.operation().operationId(), repeated.operation().operationId());
            assertEquals(1L, started.operation().targetCount());
            assertTrue(fixture.definitionDeleted(seed.definitionId()));
            assertEquals(1L, fixture.deletedMarkerCount());
            assertEquals(1L, fixture.destructiveTargetCount());
        }
    }

    @Test
    void confirmationRejectsExactTargetMovementBeforeAcceptance() {
        try (SQLiteDestructiveTestFixture fixture = fixture("stale-target-snapshot.db")) {
            var seed = fixture.seed(true);
            var administration = fixture.administration();
            var preview = fixture.preview(
                    DestructiveOperationType.EXACT_INSTANCE_REMOVAL,
                    seed,
                    seed.instanceId());

            fixture.moveCurrentState(seed.instanceId(), "player:moved-before-confirmation");
            var result = administration.start(new StartRequest(
                            preview, ADMIN_ACTOR, "stale-target-snapshot"))
                    .toCompletableFuture().join();

            assertEquals(StartStatus.STALE_CONFIRMATION, result.status());
        }
    }

    @Test
    void pauseFencesClaimsAndVerifiedRemovalCompletesTheParent() {
        try (SQLiteDestructiveTestFixture fixture = fixture("pause.db")) {
            var seed = fixture.seed(true);
            var administration = fixture.administration();
            var preview = fixture.preview(
                    DestructiveOperationType.PURGE_DEFINITION, seed, null);
            var started = administration.start(new StartRequest(
                            preview, ADMIN_ACTOR, "purge-request-1"))
                    .toCompletableFuture().join();
            UUID operationId = started.operation().operationId();

            administration.pause(new ControlRequest(operationId, ADMIN_ACTOR))
                    .toCompletableFuture().join();
            var execution = fixture.execution(30L);
            assertEquals(Status.NO_PENDING_WORK, execution.prepare(fixture.observation(seed, LOCATION_KEY))
                    .toCompletableFuture().join().status());

            administration.resume(new ControlRequest(operationId, ADMIN_ACTOR))
                    .toCompletableFuture().join();
            var prepared = execution.prepare(fixture.observation(seed, LOCATION_KEY))
                    .toCompletableFuture().join();
            assertEquals(Status.PREPARED, prepared.status());
            assertTrue(execution.complete(prepared.preparedRemoval(), FINGERPRINT)
                    .toCompletableFuture().join());

            var operation = administration.listOperations(PageRequest.first(10))
                    .toCompletableFuture().join().items().getFirst();
            assertEquals(DestructiveOperationState.COMPLETED, operation.state());
            assertEquals(1L, operation.completedCount());
            assertEquals("REMOVED", fixture.instanceLifecycle(seed.instanceId()));
        }
    }

    @Test
    void lateDeleteTargetPreservesParentPauseFence() {
        try (SQLiteDestructiveTestFixture fixture = fixture("paused-late-delete.db")) {
            var seed = fixture.seed(true);
            var administration = fixture.administration();
            var preview = fixture.preview(
                    DestructiveOperationType.DELETE_DEFINITION, seed, null);
            var started = administration.start(new StartRequest(
                            preview, ADMIN_ACTOR, "paused-late-delete"))
                    .toCompletableFuture().join();
            UUID operationId = started.operation().operationId();
            administration.pause(new ControlRequest(operationId, ADMIN_ACTOR))
                    .toCompletableFuture().join();

            LoreInstanceId lateInstanceId = new LoreInstanceId(UUID.randomUUID());
            Observation lateCopy = new Observation(
                    new LoreItemIdentity(
                            seed.definitionId(), lateInstanceId, new TemplateRevision(1L)),
                    LOCATION_TYPE,
                    "player:late-copy",
                    null,
                    FINGERPRINT);
            var execution = fixture.execution(30L);

            assertEquals(Status.NO_PENDING_WORK, execution.prepare(lateCopy)
                    .toCompletableFuture().join().status());
            var paused = administration.listOperations(PageRequest.first(10))
                    .toCompletableFuture().join().items().getFirst();
            assertEquals(DestructiveOperationState.PAUSED, paused.state());
            assertEquals(2L, paused.targetCount());

            administration.resume(new ControlRequest(operationId, ADMIN_ACTOR))
                    .toCompletableFuture().join();
            assertEquals(Status.PREPARED, execution.prepare(lateCopy)
                    .toCompletableFuture().join().status());
        }
    }

    @Test
    void unknownPhysicalOutcomePersistsAsAmbiguousReviewEvidence() {
        try (SQLiteDestructiveTestFixture fixture = fixture("unknown-review.db")) {
            var seed = fixture.seed(true);
            var administration = fixture.administration();
            var preview = fixture.preview(
                    DestructiveOperationType.PURGE_DEFINITION, seed, null);
            var started = administration.start(new StartRequest(
                            preview, ADMIN_ACTOR, "unknown-review"))
                    .toCompletableFuture().join();
            var execution = fixture.execution(30L);
            var prepared = execution.prepare(fixture.observation(seed, LOCATION_KEY))
                    .toCompletableFuture().join();
            assertEquals(Status.PREPARED, prepared.status());

            assertTrue(execution.requireReview(
                            prepared.preparedRemoval(),
                            DestructiveEffectState.UNKNOWN,
                            FINGERPRINT,
                            null,
                            "The Paper mutation outcome could not be classified.")
                    .toCompletableFuture().join());

            var target = administration.listTargets(
                            started.operation().operationId(), PageRequest.first(10))
                    .toCompletableFuture().join().items().getFirst();
            assertEquals(DestructiveTargetState.REVIEW_REQUIRED, target.state());
            assertEquals(DestructiveEffectState.AMBIGUOUS, target.effectState());
        }
    }

    @Test
    void exactRemovalMovementRequiresEvidenceReview() {
        try (SQLiteDestructiveTestFixture fixture = fixture("exact-review.db")) {
            var seed = fixture.seed(true);
            var administration = fixture.administration();
            var preview = fixture.preview(
                    DestructiveOperationType.EXACT_INSTANCE_REMOVAL,
                    seed,
                    seed.instanceId());
            var started = administration.start(new StartRequest(
                            preview, ADMIN_ACTOR, "exact-request-1"))
                    .toCompletableFuture().join();

            var prepare = fixture.execution(30L).prepare(fixture.observation(seed, "player:two"))
                    .toCompletableFuture().join();
            assertEquals(Status.REVIEW_REQUIRED, prepare.status());

            var target = administration.listTargets(
                            started.operation().operationId(), PageRequest.first(10))
                    .toCompletableFuture().join().items().getFirst();
            assertEquals(DestructiveTargetState.REVIEW_REQUIRED, target.state());
            assertEquals(DestructiveEffectState.NONE_OBSERVED, target.effectState());

            var invalid = administration.resolveReview(new ReviewRequest(
                            started.operation().operationId(),
                            seed.instanceId(),
                            ReviewResolution.MARK_VERIFIED_REMOVED,
                            ADMIN_ACTOR,
                            "The item moved; absence was not verified."))
                    .toCompletableFuture().join();
            assertEquals(ReviewStatus.EVIDENCE_MISMATCH, invalid.status());

            var requeued = administration.resolveReview(new ReviewRequest(
                            started.operation().operationId(),
                            seed.instanceId(),
                            ReviewResolution.REQUEUE_NO_SIDE_EFFECT,
                            ADMIN_ACTOR,
                            "The original slot still contains the untouched target."))
                    .toCompletableFuture().join();
            assertEquals(ReviewStatus.RESOLVED, requeued.status());
            assertEquals(DestructiveTargetState.PENDING, requeued.target().state());
        }
    }

    @Test
    void expiredClaimBecomesAmbiguousAndCanBeResolvedAfterStaffInspection() {
        try (SQLiteDestructiveTestFixture fixture = fixture("expired.db")) {
            var seed = fixture.seed(true);
            var administration = fixture.administration();
            var preview = fixture.preview(
                    DestructiveOperationType.PURGE_DEFINITION, seed, null);
            var started = administration.start(new StartRequest(
                            preview, ADMIN_ACTOR, "purge-expired"))
                    .toCompletableFuture().join();
            var execution = fixture.execution(1L);
            assertEquals(Status.PREPARED, execution.prepare(fixture.observation(seed, LOCATION_KEY))
                    .toCompletableFuture().join().status());

            assertEquals(1, fixture.store().moveExpiredClaimsToReview(
                            Instant.ofEpochMilli(3_001L), 10)
                    .toCompletableFuture().join());
            var target = administration.listTargets(
                            started.operation().operationId(), PageRequest.first(10))
                    .toCompletableFuture().join().items().getFirst();
            assertEquals(DestructiveTargetState.REVIEW_REQUIRED, target.state());
            assertEquals(DestructiveEffectState.AMBIGUOUS, target.effectState());
            assertEquals(Status.NO_PENDING_WORK, execution.prepare(fixture.observation(seed, LOCATION_KEY))
                    .toCompletableFuture().join().status());

            var reviewed = administration.resolveReview(new ReviewRequest(
                            started.operation().operationId(),
                            seed.instanceId(),
                            ReviewResolution.REQUEUE_NO_SIDE_EFFECT,
                            ADMIN_ACTOR,
                            "Staff physically verified the original target remains present and unchanged."))
                    .toCompletableFuture().join();
            assertEquals(ReviewStatus.RESOLVED, reviewed.status());
            assertEquals(DestructiveTargetState.PENDING, reviewed.target().state());
            assertEquals(DestructiveEffectState.NONE_OBSERVED, reviewed.target().effectState());
        }
    }

    @Test
    void lateCopyAfterEmptyFullDeleteCreatesTargetAndReopensOperation() {
        try (SQLiteDestructiveTestFixture fixture = fixture("late-delete.db")) {
            var seed = fixture.seed(false);
            var administration = fixture.administration();
            var preview = fixture.preview(
                    DestructiveOperationType.DELETE_DEFINITION, seed, null);
            var started = administration.start(new StartRequest(
                            preview, ADMIN_ACTOR, "empty-delete"))
                    .toCompletableFuture().join();
            assertEquals(DestructiveOperationState.COMPLETED, started.operation().state());
            assertEquals(0L, started.operation().targetCount());

            var execution = fixture.execution(30L);
            var prepared = execution.prepare(fixture.observation(seed, LOCATION_KEY))
                    .toCompletableFuture().join();
            assertEquals(Status.PREPARED, prepared.status());
            assertTrue(execution.complete(prepared.preparedRemoval(), FINGERPRINT)
                    .toCompletableFuture().join());

            var operation = administration.listOperations(PageRequest.first(10))
                    .toCompletableFuture().join().items().getFirst();
            assertEquals(1L, operation.targetCount());
            assertEquals(1L, operation.completedCount());
            assertEquals(DestructiveOperationState.COMPLETED, operation.state());
            assertEquals("REMOVED", fixture.instanceLifecycle(seed.instanceId()));
            assertFalse(operation.remainingCount() > 0L);
        }
    }

    private SQLiteDestructiveTestFixture fixture(String fileName) {
        return new SQLiteDestructiveTestFixture(temporaryDirectory, fileName, CLOCK);
    }
}