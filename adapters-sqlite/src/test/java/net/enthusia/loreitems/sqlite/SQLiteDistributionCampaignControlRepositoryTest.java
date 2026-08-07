package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.enthusia.loreitems.application.AuditEventRecord;
import net.enthusia.loreitems.application.CampaignCancellationResult;
import net.enthusia.loreitems.application.DistributionCampaignStartRequest;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.domain.CampaignRecipient;
import net.enthusia.loreitems.domain.DistributionCampaign;
import net.enthusia.loreitems.domain.DistributionCampaignState;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SQLiteDistributionCampaignControlRepositoryTest {
    private static final long CREATED_AT = 10_000L;

    @TempDir
    Path temporaryDirectory;

    @Test
    void pauseAndCancelCommitAuditWithControlState() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("controls.db"));
        try {
            UUID campaignId = startCampaign(runtime);
            SQLiteDistributionCampaignControlRepository controls =
                    new SQLiteDistributionCampaignControlRepository(runtime);
            Instant pausedAt = Instant.ofEpochMilli(20_000L);
            boolean paused = controls.transitionWithAudit(
                            campaignId,
                            DistributionCampaignState.ACTIVE,
                            DistributionCampaignState.PAUSED,
                            pausedAt,
                            audit(campaignId, "DISTRIBUTION_CAMPAIGN_PAUSED", pausedAt))
                    .toCompletableFuture().join();
            assertTrue(paused);
            assertEquals(DistributionCampaignState.PAUSED, campaignState(runtime, campaignId));

            Instant cancelledAt = Instant.ofEpochMilli(30_000L);
            CampaignCancellationResult cancelled = controls.cancelWithAudit(
                            campaignId,
                            DistributionCampaignState.PAUSED,
                            cancelledAt,
                            "DISTRIBUTION_CAMPAIGN_CANCELLED",
                            "PLAYER",
                            "operator")
                    .toCompletableFuture().join();
            assertTrue(cancelled.cancelled());
            assertEquals(1, cancelled.recipientsCancelled());
            assertEquals(DistributionCampaignState.CANCELLED, campaignState(runtime, campaignId));
            assertEquals(
                    List.of(
                            "DISTRIBUTION_CAMPAIGN_CANCELLED",
                            "DISTRIBUTION_CAMPAIGN_PAUSED",
                            "DISTRIBUTION_CAMPAIGN_STARTED"),
                    auditTypes(runtime, campaignId));
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void globalReviewQueueReturnsOnlyReviewRequiredRecipients() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("review.db"));
        try {
            UUID firstCampaign = startCampaign(runtime);
            UUID secondCampaign = startCampaign(runtime);
            markReviewRequired(runtime, firstCampaign);

            List<CampaignRecipient> review = new SQLiteDistributionReviewRepository(runtime)
                    .listReviewRequired(PageRequest.first(10))
                    .toCompletableFuture().join().items();
            assertEquals(1, review.size());
            assertEquals(firstCampaign, review.getFirst().campaignId());
            assertTrue(review.stream().noneMatch(row -> row.campaignId().equals(secondCampaign)));
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private static UUID startCampaign(SQLiteStorageRuntime runtime) {
        UUID definitionId = seedDefinition(runtime);
        UUID campaignId = UUID.randomUUID();
        DistributionCampaign campaign = new DistributionCampaign(
                campaignId,
                "sha256:" + campaignId,
                "group-" + campaignId + ".yml",
                "Group",
                new LoreDefinitionId(definitionId),
                new TemplateRevision(1L),
                DistributionCampaignState.DRAFT,
                CREATED_AT,
                CREATED_AT,
                null);
        CampaignRecipient recipient =
                CampaignRecipient.unresolvedName(campaignId, 0, "Player-" + campaignId, CREATED_AT);
        new SQLiteDistributionCampaignStartRepository(runtime)
                .start(new DistributionCampaignStartRequest(
                        campaign, List.of(recipient), "PLAYER", "operator"))
                .toCompletableFuture().join();
        return campaignId;
    }

    private static UUID seedDefinition(SQLiteStorageRuntime runtime) {
        UUID definitionId = UUID.randomUUID();
        runtime.execute(connection -> {
            try (PreparedStatement definition = connection.prepareStatement(
                    "INSERT INTO lore_definitions(definition_id, lookup_key, display_name, "
                            + "current_revision, created_at) VALUES (?, ?, 'Definition', 1, ?)");
                    PreparedStatement revision = connection.prepareStatement(
                            "INSERT INTO lore_definition_revisions(definition_id, revision, "
                                    + "codec_version, template_blob, created_at) "
                                    + "VALUES (?, 1, 1, ?, ?)")) {
                definition.setString(1, definitionId.toString());
                definition.setString(2, "definition-" + definitionId);
                definition.setLong(3, CREATED_AT);
                definition.executeUpdate();
                revision.setString(1, definitionId.toString());
                revision.setBytes(2, new byte[] {1});
                revision.setLong(3, CREATED_AT);
                revision.executeUpdate();
            }
            return null;
        }).toCompletableFuture().join();
        return definitionId;
    }

    private static void markReviewRequired(SQLiteStorageRuntime runtime, UUID campaignId) {
        runtime.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE distribution_recipients SET state = 'REVIEW_REQUIRED' "
                            + "WHERE campaign_id = ?")) {
                statement.setString(1, campaignId.toString());
                statement.executeUpdate();
            }
            return null;
        }).toCompletableFuture().join();
    }

    private static DistributionCampaignState campaignState(
            SQLiteStorageRuntime runtime, UUID campaignId) {
        return new SQLiteDistributionCampaignRepository(runtime)
                .findById(campaignId)
                .toCompletableFuture().join().orElseThrow().state();
    }

    private static List<String> auditTypes(SQLiteStorageRuntime runtime, UUID campaignId) {
        return new SQLiteAuditRepository(runtime)
                .listByAggregate(
                        "DISTRIBUTION_CAMPAIGN", campaignId.toString(), PageRequest.first(10))
                .toCompletableFuture().join().items().stream()
                .map(AuditEventRecord::eventType)
                .toList();
    }

    private static AuditEventRecord audit(UUID campaignId, String eventType, Instant now) {
        return AuditEventRecord.pending(
                "DISTRIBUTION_CAMPAIGN",
                campaignId.toString(),
                eventType,
                "PLAYER",
                "operator",
                "{}",
                now.toEpochMilli());
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
