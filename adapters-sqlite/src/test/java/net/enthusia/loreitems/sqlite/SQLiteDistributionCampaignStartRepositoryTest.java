package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import net.enthusia.loreitems.application.AuditEventRecord;
import net.enthusia.loreitems.application.DistributionCampaignStartRequest;
import net.enthusia.loreitems.application.DistributionCampaignStartResult;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.domain.CampaignRecipient;
import net.enthusia.loreitems.domain.DistributionCampaign;
import net.enthusia.loreitems.domain.DistributionCampaignState;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SQLiteDistributionCampaignStartRepositoryTest {
    private static final long CREATED_AT = 10_000L;

    @TempDir
    Path temporaryDirectory;

    @Test
    void startPinsRevisionPersistsSnapshotAuditAndRefusesReplay() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("start.db"));
        try {
            UUID definitionId = seedDefinition(runtime, 2L);
            UUID campaignId = UUID.randomUUID();
            DistributionCampaign campaign = campaign(campaignId, definitionId, 2L, "sha256:atomic-source");
            List<CampaignRecipient> recipients = List.of(
                    CampaignRecipient.unresolvedName(campaignId, 0, "JavaPlayer", CREATED_AT),
                    CampaignRecipient.knownPlayer(
                            campaignId, 1, UUID.randomUUID(), "00000000-0000-0000-0000-000000000001", CREATED_AT));
            SQLiteDistributionCampaignStartRepository starts =
                    new SQLiteDistributionCampaignStartRepository(runtime);

            DistributionCampaignStartResult started = starts.start(new DistributionCampaignStartRequest(
                            campaign, recipients, "PLAYER", "operator-1"))
                    .toCompletableFuture().join();
            assertEquals(DistributionCampaignStartResult.Status.STARTED, started.status());

            DistributionCampaign stored = new SQLiteDistributionCampaignRepository(runtime)
                    .findById(campaignId).toCompletableFuture().join().orElseThrow();
            assertEquals(DistributionCampaignState.ACTIVE, stored.state());
            assertEquals(new TemplateRevision(2L), stored.definitionRevision());
            assertEquals(2L, new SQLiteDistributionRecipientRepository(runtime)
                    .countByState(campaignId).toCompletableFuture().join().total());

            List<AuditEventRecord> audit = new SQLiteAuditRepository(runtime)
                    .listByAggregate("DISTRIBUTION_CAMPAIGN", campaignId.toString(), PageRequest.first(10))
                    .toCompletableFuture().join().items();
            assertEquals(1, audit.size());
            assertEquals("DISTRIBUTION_CAMPAIGN_STARTED", audit.getFirst().eventType());
            assertEquals("operator-1", audit.getFirst().actorId());

            UUID replayId = UUID.randomUUID();
            DistributionCampaign replay = campaign(replayId, definitionId, 2L, "SHA256:ATOMIC-SOURCE");
            DistributionCampaignStartResult replayed = starts.start(new DistributionCampaignStartRequest(
                            replay,
                            List.of(CampaignRecipient.unresolvedName(replayId, 0, "OtherPlayer", CREATED_AT)),
                            "PLAYER",
                            "operator-2"))
                    .toCompletableFuture().join();
            assertEquals(DistributionCampaignStartResult.Status.SOURCE_ALREADY_USED, replayed.status());
            assertEquals(campaignId, replayed.campaignId());
            assertTrue(new SQLiteDistributionCampaignRepository(runtime)
                    .findById(replayId).toCompletableFuture().join().isEmpty());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void revisionChangeRejectsWholeStartWithoutPartialCampaign() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("revision-change.db"));
        try {
            UUID definitionId = seedDefinition(runtime, 2L);
            UUID campaignId = UUID.randomUUID();
            DistributionCampaign campaign = campaign(campaignId, definitionId, 2L, "sha256:stale-preview");
            advanceDefinition(runtime, definitionId, 3L);
            SQLiteDistributionCampaignStartRepository starts =
                    new SQLiteDistributionCampaignStartRepository(runtime);

            assertThrows(CompletionException.class, () -> starts.start(new DistributionCampaignStartRequest(
                            campaign,
                            List.of(CampaignRecipient.unresolvedName(
                                    campaignId, 0, "Player", CREATED_AT)),
                            "PLAYER",
                            "operator"))
                    .toCompletableFuture().join());
            assertTrue(new SQLiteDistributionCampaignRepository(runtime)
                    .findById(campaignId).toCompletableFuture().join().isEmpty());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private static DistributionCampaign campaign(
            UUID campaignId, UUID definitionId, long revision, String fingerprint) {
        return new DistributionCampaign(
                campaignId,
                fingerprint,
                "group.yml",
                "Group",
                new LoreDefinitionId(definitionId),
                new TemplateRevision(revision),
                DistributionCampaignState.DRAFT,
                CREATED_AT,
                CREATED_AT,
                null);
    }

    private static UUID seedDefinition(SQLiteStorageRuntime runtime, long currentRevision) {
        UUID definitionId = UUID.randomUUID();
        runtime.execute(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO lore_definitions(definition_id, lookup_key, display_name, "
                            + "current_revision, created_at) VALUES (?, ?, ?, ?, ?)")) {
                statement.setString(1, definitionId.toString());
                statement.setString(2, "definition-" + definitionId);
                statement.setString(3, "Definition");
                statement.setLong(4, currentRevision);
                statement.setLong(5, 1L);
                statement.executeUpdate();
            }
            for (long revision = 1L; revision <= currentRevision; revision++) {
                insertRevision(connection, definitionId, revision);
            }
            return null;
        }).toCompletableFuture().join();
        return definitionId;
    }

    private static void advanceDefinition(
            SQLiteStorageRuntime runtime, UUID definitionId, long revision) {
        runtime.execute(connection -> {
            insertRevision(connection, definitionId, revision);
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE lore_definitions SET current_revision = ? WHERE definition_id = ?")) {
                statement.setLong(1, revision);
                statement.setString(2, definitionId.toString());
                statement.executeUpdate();
            }
            return null;
        }).toCompletableFuture().join();
    }

    private static void insertRevision(
            java.sql.Connection connection, UUID definitionId, long revision) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO lore_definition_revisions(definition_id, revision, codec_version, "
                        + "template_blob, created_at) VALUES (?, ?, 1, ?, ?)")) {
            statement.setString(1, definitionId.toString());
            statement.setLong(2, revision);
            statement.setBytes(3, new byte[] {(byte) revision});
            statement.setLong(4, revision);
            statement.executeUpdate();
        }
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
