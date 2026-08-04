package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import net.enthusia.loreitems.application.AuditEventRecord;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.PendingMutationRecord;
import net.enthusia.loreitems.application.PendingMutationReviewStore;
import net.enthusia.loreitems.application.TemplateRevisionStartResult;
import net.enthusia.loreitems.application.TemplateRevisionStartStatus;
import net.enthusia.loreitems.domain.DefinitionKey;
import net.enthusia.loreitems.domain.LoreDefinition;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreDefinitionRevision;
import net.enthusia.loreitems.domain.LoreInstance;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.LoreInstanceLifecycle;
import net.enthusia.loreitems.domain.PendingMutationState;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SQLiteTemplateRevisionCancellationTest {
    private static final String TEMPLATE_UPDATE = "TEMPLATE_UPDATE";
    private static final TemplateRevision REVISION_ONE = new TemplateRevision(1);
    private static final TemplateRevision REVISION_TWO = new TemplateRevision(2);
    private static final TemplateRevision REVISION_THREE = new TemplateRevision(3);

    @TempDir
    Path temporaryDirectory;

    @Test
    void cancelledReviewedWorkDoesNotBlockAReplacementRevision() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("cancelled-rollout.db"));
        try {
            LoreDefinitionId definitionId = seedDefinitionAndInstance(runtime);
            SQLiteTemplateRevisionRolloutStore rolloutStore =
                    new SQLiteTemplateRevisionRolloutStore(runtime);
            TemplateRevisionStartResult first = rolloutStore.start(
                            revision(definitionId, REVISION_TWO, 2_000L),
                            REVISION_ONE,
                            revisionAudit(definitionId, 1L, 2L, 2_000L),
                            10)
                    .toCompletableFuture().join();
            PendingMutationRecord claimed = moveFirstMutationToReview(runtime);
            PendingMutationReviewStore.Status cancelled =
                    new SQLitePendingMutationReviewStore(runtime).resolve(
                                    claimed.mutationId(),
                                    TEMPLATE_UPDATE,
                                    PendingMutationReviewStore.Resolution.CANCEL,
                                    mutationAudit(claimed.mutationId(), 3_000L),
                                    Instant.ofEpochMilli(3_000L))
                            .toCompletableFuture().join();

            TemplateRevisionStartResult replacement = rolloutStore.start(
                            revision(definitionId, REVISION_THREE, 4_000L),
                            REVISION_TWO,
                            revisionAudit(definitionId, 2L, 3L, 4_000L),
                            10)
                    .toCompletableFuture().join();

            assertEquals(TemplateRevisionStartStatus.STARTED, first.status());
            assertEquals(PendingMutationReviewStore.Status.CANCELLED, cancelled);
            assertEquals(TemplateRevisionStartStatus.STARTED, replacement.status());
            assertEquals(REVISION_THREE, replacement.currentRevision());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private static LoreDefinitionId seedDefinitionAndInstance(SQLiteStorageRuntime runtime) {
        LoreDefinitionId definitionId = new LoreDefinitionId(UUID.randomUUID());
        LoreDefinitionRevision initialRevision = revision(definitionId, REVISION_ONE, 1_000L);
        new SQLiteDefinitionRepository(runtime).create(
                        new LoreDefinition(
                                definitionId,
                                new DefinitionKey("cancelled-rollout"),
                                "Cancelled Rollout",
                                REVISION_ONE,
                                1_000L,
                                null),
                        initialRevision)
                .toCompletableFuture().join();
        new SQLiteInstanceRepository(runtime).create(new LoreInstance(
                        new LoreInstanceId(UUID.randomUUID()),
                        definitionId,
                        REVISION_ONE,
                        REVISION_ONE,
                        LoreInstanceLifecycle.ACTIVE,
                        1_000L,
                        null))
                .toCompletableFuture().join();
        return definitionId;
    }

    private static PendingMutationRecord moveFirstMutationToReview(
            SQLiteStorageRuntime runtime) {
        SQLitePendingMutationRepository repository =
                new SQLitePendingMutationRepository(runtime);
        PendingMutationRecord claimed = repository.claimPending(
                        TEMPLATE_UPDATE,
                        "template-worker",
                        Instant.ofEpochMilli(2_500L),
                        Duration.ofSeconds(30),
                        1)
                .toCompletableFuture().join().items().getFirst();
        assertTrue(repository.transitionClaimed(
                        claimed.mutationId(),
                        PendingMutationState.CLAIMED,
                        PendingMutationState.REVIEW_REQUIRED,
                        claimed.claimToken(),
                        Instant.ofEpochMilli(2_600L))
                .toCompletableFuture().join());
        return claimed;
    }

    private static LoreDefinitionRevision revision(
            LoreDefinitionId definitionId, TemplateRevision revision, long createdAt) {
        return new LoreDefinitionRevision(
                definitionId,
                revision,
                1,
                new byte[] {(byte) revision.value()},
                createdAt);
    }

    private static AuditEventRecord revisionAudit(
            LoreDefinitionId definitionId,
            long previousRevision,
            long targetRevision,
            long occurredAt) {
        return AuditEventRecord.pending(
                "lore_definition",
                definitionId.value().toString(),
                "template_revision_started",
                "STAFF",
                "test-operator",
                "{\"previousRevision\":" + previousRevision
                        + ",\"targetRevision\":" + targetRevision + "}",
                occurredAt);
    }

    private static AuditEventRecord mutationAudit(UUID mutationId, long occurredAt) {
        return AuditEventRecord.pending(
                "pending_mutation",
                mutationId.toString(),
                "mutation_review_cancelled",
                "STAFF",
                "test-operator",
                "{\"reason\":\"superseded by corrected revision\"}",
                occurredAt);
    }

    private static SQLiteStorageRuntime start(Path database) {
        MetricsPort metrics = MetricsPort.noOp();
        SQLiteStorageRuntime runtime = new SQLiteStorageRuntime(
                new SQLiteConnectionFactory(database, 5_000),
                new MigrationRunner(),
                new BoundedDatabaseExecutor("test-database", 32, metrics),
                metrics);
        assertEquals(
                net.enthusia.loreitems.application.StorageState.READ_WRITE,
                runtime.start().toCompletableFuture().join().state());
        return runtime;
    }
}
