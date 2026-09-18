package net.enthusia.loreitems.sqlite;

import static net.enthusia.loreitems.sqlite.SQLiteDestructiveTestFixture.FINGERPRINT;
import static net.enthusia.loreitems.sqlite.SQLiteDestructiveTestFixture.LOCATION_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.ReviewRequest;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.ReviewResolution;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.ReviewStatus;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.StartRequest;
import net.enthusia.loreitems.application.DestructiveRemovalExecutionUseCase.Status;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.domain.DestructiveEffectState;
import net.enthusia.loreitems.domain.DestructiveOperationState;
import net.enthusia.loreitems.domain.DestructiveOperationType;
import net.enthusia.loreitems.domain.DestructiveTargetState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SQLiteDestructiveAmbiguousReviewRecoveryTest {
    private static final Instant NOW = Instant.ofEpochMilli(2_000L);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String ADMIN_ACTOR = "admin";

    @TempDir
    Path temporaryDirectory;

    @Test
    void expiredClaimCanBeRequeuedAfterStaffVerifiesNoSideEffect() {
        try (SQLiteDestructiveTestFixture fixture = fixture("expired-reviewed.db")) {
            var seed = fixture.seed(true);
            var administration = fixture.administration();
            var preview = fixture.preview(
                    DestructiveOperationType.PURGE_DEFINITION, seed, null);
            var started = administration.start(new StartRequest(
                            preview, ADMIN_ACTOR, "expired-reviewed"))
                    .toCompletableFuture().join();
            var execution = fixture.execution(1L);
            assertEquals(Status.PREPARED, execution.prepare(fixture.observation(seed, LOCATION_KEY))
                    .toCompletableFuture().join().status());

            assertEquals(1, fixture.store().moveExpiredClaimsToReview(
                            Instant.ofEpochMilli(3_001L), 10)
                    .toCompletableFuture().join());
            var ambiguous = administration.listTargets(
                            started.operation().operationId(), PageRequest.first(10))
                    .toCompletableFuture().join().items().getFirst();
            assertEquals(DestructiveTargetState.REVIEW_REQUIRED, ambiguous.state());
            assertEquals(DestructiveEffectState.AMBIGUOUS, ambiguous.effectState());

            var resolved = administration.resolveReview(new ReviewRequest(
                            started.operation().operationId(),
                            seed.instanceId(),
                            ReviewResolution.REQUEUE_NO_SIDE_EFFECT,
                            ADMIN_ACTOR,
                            "Staff inspected the target and verified that the item was not removed."))
                    .toCompletableFuture().join();

            assertEquals(ReviewStatus.RESOLVED, resolved.status());
            assertEquals(DestructiveTargetState.PENDING, resolved.target().state());
            assertEquals(DestructiveEffectState.NONE_OBSERVED, resolved.target().effectState());
            assertEquals("ACTIVE", fixture.instanceLifecycle(seed.instanceId()));
        }
    }

    @Test
    void ambiguousOutcomeCanBeCompletedAfterStaffVerifiesRemoval() {
        try (SQLiteDestructiveTestFixture fixture = fixture("ambiguous-removed.db")) {
            var seed = fixture.seed(true);
            var administration = fixture.administration();
            var preview = fixture.preview(
                    DestructiveOperationType.PURGE_DEFINITION, seed, null);
            var started = administration.start(new StartRequest(
                            preview, ADMIN_ACTOR, "ambiguous-removed"))
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
                            "The Paper mutation outcome could not be classified automatically."))
                    .toCompletableFuture().join());

            var resolved = administration.resolveReview(new ReviewRequest(
                            started.operation().operationId(),
                            seed.instanceId(),
                            ReviewResolution.MARK_VERIFIED_REMOVED,
                            ADMIN_ACTOR,
                            "Staff inspected the location and verified that the item was removed."))
                    .toCompletableFuture().join();

            assertEquals(ReviewStatus.RESOLVED, resolved.status());
            assertEquals(DestructiveTargetState.COMPLETED, resolved.target().state());
            assertEquals(DestructiveEffectState.REMOVED_OBSERVED, resolved.target().effectState());
            assertEquals("REMOVED", fixture.instanceLifecycle(seed.instanceId()));
            var operation = administration.listOperations(PageRequest.first(10))
                    .toCompletableFuture().join().items().getFirst();
            assertEquals(DestructiveOperationState.COMPLETED, operation.state());
        }
    }

    private SQLiteDestructiveTestFixture fixture(String fileName) {
        return new SQLiteDestructiveTestFixture(temporaryDirectory, fileName, CLOCK);
    }
}
