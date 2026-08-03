package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import net.enthusia.loreitems.application.CampaignCancellationResult;
import net.enthusia.loreitems.application.CampaignRecipientCounts;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.domain.CampaignRecipient;
import net.enthusia.loreitems.domain.CampaignRecipientKey;
import net.enthusia.loreitems.domain.CampaignRecipientState;
import net.enthusia.loreitems.domain.DistributionCampaign;
import net.enthusia.loreitems.domain.DistributionCampaignState;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SQLiteDistributionRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void sourceAndRecipientSnapshotIdentityRemainImmutable() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("immutable.db"));
        try {
            UUID definitionId = seedDefinition(runtime);
            SQLiteDistributionCampaignRepository campaigns =
                    new SQLiteDistributionCampaignRepository(runtime);
            SQLiteDistributionRecipientRepository recipients =
                    new SQLiteDistributionRecipientRepository(runtime);
            DistributionCampaign campaign = campaign(definitionId, "sha256:source-a", 1_000L);
            campaigns.create(campaign).toCompletableFuture().join();

            DistributionCampaign duplicate = new DistributionCampaign(
                    UUID.randomUUID(),
                    "SHA256:SOURCE-A",
                    "copied.yml",
                    "Copied",
                    new LoreDefinitionId(definitionId),
                    DistributionCampaignState.DRAFT,
                    1_001L,
                    1_001L,
                    null);
            assertThrows(
                    CompletionException.class,
                    () -> campaigns.create(duplicate).toCompletableFuture().join());

            CampaignRecipient recipient = CampaignRecipient.unresolvedName(
                    campaign.campaignId(), 0, "*OriginalName", 1_010L);
            recipients.insertBatch(campaign.campaignId(), List.of(recipient))
                    .toCompletableFuture()
                    .join();
            assertThrows(
                    CompletionException.class,
                    () -> runtime.execute(connection -> {
                        try (PreparedStatement statement = connection.prepareStatement(
                                "UPDATE distribution_recipients SET original_value = ? "
                                        + "WHERE campaign_id = ? AND recipient_key = ?")) {
                            statement.setString(1, "ReplacementName");
                            statement.setString(2, campaign.campaignId().toString());
                            statement.setString(3, recipient.recipientKey().value());
                            statement.executeUpdate();
                            return null;
                        }
                    }).toCompletableFuture().join());

            assertTrue(campaigns.transitionState(
                            campaign.campaignId(),
                            DistributionCampaignState.DRAFT,
                            DistributionCampaignState.ACTIVE,
                            Instant.ofEpochMilli(1_100L))
                    .toCompletableFuture()
                    .join());
            assertThrows(
                    CompletionException.class,
                    () -> recipients.insertBatch(
                                    campaign.campaignId(),
                                    List.of(CampaignRecipient.unresolvedName(
                                            campaign.campaignId(),
                                            1,
                                            "LateAddition",
                                            1_101L)))
                            .toCompletableFuture()
                            .join());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void unresolvedNamesBindCaseInsensitivelyWithBoundedPagesAndCounts() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("binding.db"));
        try {
            UUID definitionId = seedDefinition(runtime);
            SQLiteDistributionCampaignRepository campaigns =
                    new SQLiteDistributionCampaignRepository(runtime);
            SQLiteDistributionRecipientRepository recipients =
                    new SQLiteDistributionRecipientRepository(runtime);
            DistributionCampaign campaign = campaign(definitionId, "sha256:binding", 2_000L);
            campaigns.create(campaign).toCompletableFuture().join();
            UUID knownPlayer = UUID.randomUUID();
            CampaignRecipient floodgate = CampaignRecipient.unresolvedName(
                    campaign.campaignId(), 0, "*BedRockUser", 2_010L);
            CampaignRecipient alias = CampaignRecipient.unresolvedName(
                    campaign.campaignId(), 1, "OtherAlias", 2_011L);
            CampaignRecipient known = CampaignRecipient.knownPlayer(
                    campaign.campaignId(), 2, knownPlayer, knownPlayer.toString(), 2_012L);
            recipients.insertBatch(campaign.campaignId(), List.of(floodgate, alias, known))
                    .toCompletableFuture()
                    .join();
            assertTrue(campaigns.transitionState(
                            campaign.campaignId(),
                            DistributionCampaignState.DRAFT,
                            DistributionCampaignState.ACTIVE,
                            Instant.ofEpochMilli(2_100L))
                    .toCompletableFuture()
                    .join());

            Page<CampaignRecipient> firstPage = recipients
                    .listByCampaign(campaign.campaignId(), PageRequest.first(2))
                    .toCompletableFuture()
                    .join();
            Page<CampaignRecipient> secondPage = recipients
                    .listByCampaign(campaign.campaignId(), PageRequest.first(2).next())
                    .toCompletableFuture()
                    .join();
            assertEquals(2, firstPage.items().size());
            assertTrue(firstPage.hasMore());
            assertEquals(1, secondPage.items().size());
            assertFalse(secondPage.hasMore());

            CampaignRecipientKey lookup =
                    CampaignRecipientKey.forUnresolvedName("*BEDROCKUSER");
            Page<CampaignRecipient> unresolved = recipients
                    .listUnresolvedByKey(lookup, PageRequest.first(10))
                    .toCompletableFuture()
                    .join();
            assertEquals(1, unresolved.items().size());
            assertEquals("*BedRockUser", unresolved.items().getFirst().originalValue());

            UUID joinedPlayer = UUID.randomUUID();
            assertTrue(recipients.bindUnresolvedName(
                            campaign.campaignId(),
                            lookup,
                            joinedPlayer,
                            Instant.ofEpochMilli(2_200L))
                    .toCompletableFuture()
                    .join());
            assertFalse(recipients.bindUnresolvedName(
                            campaign.campaignId(),
                            alias.recipientKey(),
                            joinedPlayer,
                            Instant.ofEpochMilli(2_201L))
                    .toCompletableFuture()
                    .join());

            CampaignRecipient rebound = recipients
                    .find(campaign.campaignId(), lookup)
                    .toCompletableFuture()
                    .join()
                    .orElseThrow();
            CampaignRecipientCounts counts = recipients
                    .countByState(campaign.campaignId())
                    .toCompletableFuture()
                    .join();
            assertEquals(joinedPlayer, rebound.playerId());
            assertEquals("name:*bedrockuser", rebound.recipientKey().value());
            assertEquals("*BedRockUser", rebound.originalValue());
            assertEquals(1L, counts.pendingName());
            assertEquals(2L, counts.pendingOffline());
            assertEquals(3L, counts.total());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void claimsAreFencedAndCancellationPreservesDeliveredOrInFlightRecipients() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("cancel.db"));
        try {
            UUID definitionId = seedDefinition(runtime);
            SQLiteDistributionCampaignRepository campaigns =
                    new SQLiteDistributionCampaignRepository(runtime);
            SQLiteDistributionRecipientRepository recipients =
                    new SQLiteDistributionRecipientRepository(runtime);
            DistributionCampaign campaign = campaign(definitionId, "sha256:cancel", 3_000L);
            campaigns.create(campaign).toCompletableFuture().join();
            List<CampaignRecipient> snapshot = List.of(
                    knownRecipient(campaign.campaignId(), 0, 3_010L),
                    knownRecipient(campaign.campaignId(), 1, 3_011L),
                    knownRecipient(campaign.campaignId(), 2, 3_012L));
            recipients.insertBatch(campaign.campaignId(), snapshot)
                    .toCompletableFuture()
                    .join();
            assertTrue(campaigns.transitionState(
                            campaign.campaignId(),
                            DistributionCampaignState.DRAFT,
                            DistributionCampaignState.ACTIVE,
                            Instant.ofEpochMilli(3_100L))
                    .toCompletableFuture()
                    .join());

            Page<CampaignRecipient> claimed = recipients
                    .claimPending(
                            campaign.campaignId(),
                            "worker-a",
                            Instant.ofEpochMilli(3_200L),
                            Duration.ofSeconds(30),
                            1)
                    .toCompletableFuture()
                    .join();
            CampaignRecipient reserved = claimed.items().getFirst();
            assertFalse(recipients.releaseClaim(
                            campaign.campaignId(),
                            reserved.recipientKey(),
                            CampaignRecipientState.PENDING_SPACE,
                            "worker-b",
                            Instant.ofEpochMilli(3_201L),
                            Instant.ofEpochMilli(4_000L))
                    .toCompletableFuture()
                    .join());

            CampaignCancellationResult cancellation = campaigns.cancel(
                            campaign.campaignId(),
                            DistributionCampaignState.ACTIVE,
                            Instant.ofEpochMilli(3_300L))
                    .toCompletableFuture()
                    .join();
            assertTrue(cancellation.cancelled());
            assertEquals(2, cancellation.recipientsCancelled());
            assertTrue(recipients.claimPending(
                            campaign.campaignId(),
                            "worker-after-cancel",
                            Instant.ofEpochMilli(3_301L),
                            Duration.ofSeconds(30),
                            10)
                    .toCompletableFuture()
                    .join()
                    .items()
                    .isEmpty());

            UUID deliveredInstance = seedInstance(runtime, definitionId, 3_250L);
            assertFalse(recipients.completeClaim(
                            campaign.campaignId(),
                            reserved.recipientKey(),
                            "worker-b",
                            new LoreInstanceId(deliveredInstance),
                            Instant.ofEpochMilli(3_400L))
                    .toCompletableFuture()
                    .join());
            assertTrue(recipients.completeClaim(
                            campaign.campaignId(),
                            reserved.recipientKey(),
                            "worker-a",
                            new LoreInstanceId(deliveredInstance),
                            Instant.ofEpochMilli(3_400L))
                    .toCompletableFuture()
                    .join());

            CampaignRecipientCounts counts = recipients
                    .countByState(campaign.campaignId())
                    .toCompletableFuture()
                    .join();
            assertEquals(1L, counts.delivered());
            assertEquals(2L, counts.cancelled());
            assertEquals(3L, counts.total());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void campaignTransitionsRequireCompleteSnapshotAndDeliveredRecipients() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("transitions.db"));
        try {
            UUID definitionId = seedDefinition(runtime);
            SQLiteDistributionCampaignRepository campaigns =
                    new SQLiteDistributionCampaignRepository(runtime);
            SQLiteDistributionRecipientRepository recipients =
                    new SQLiteDistributionRecipientRepository(runtime);
            DistributionCampaign campaign = campaign(definitionId, "sha256:transitions", 4_000L);
            campaigns.create(campaign).toCompletableFuture().join();
            recipients.insertBatch(
                            campaign.campaignId(),
                            List.of(
                                    knownRecipient(campaign.campaignId(), 0, 4_010L),
                                    knownRecipient(campaign.campaignId(), 2, 4_012L)))
                    .toCompletableFuture()
                    .join();
            assertFalse(campaigns.transitionState(
                            campaign.campaignId(),
                            DistributionCampaignState.DRAFT,
                            DistributionCampaignState.ACTIVE,
                            Instant.ofEpochMilli(4_100L))
                    .toCompletableFuture()
                    .join());
            recipients.insertBatch(
                            campaign.campaignId(),
                            List.of(knownRecipient(campaign.campaignId(), 1, 4_011L)))
                    .toCompletableFuture()
                    .join();
            assertTrue(campaigns.transitionState(
                            campaign.campaignId(),
                            DistributionCampaignState.DRAFT,
                            DistributionCampaignState.ACTIVE,
                            Instant.ofEpochMilli(4_101L))
                    .toCompletableFuture()
                    .join());
            assertTrue(campaigns.transitionState(
                            campaign.campaignId(),
                            DistributionCampaignState.ACTIVE,
                            DistributionCampaignState.PAUSED,
                            Instant.ofEpochMilli(4_102L))
                    .toCompletableFuture()
                    .join());
            assertFalse(campaigns.transitionState(
                            campaign.campaignId(),
                            DistributionCampaignState.PAUSED,
                            DistributionCampaignState.COMPLETED,
                            Instant.ofEpochMilli(4_103L))
                    .toCompletableFuture()
                    .join());
            assertTrue(campaigns.transitionState(
                            campaign.campaignId(),
                            DistributionCampaignState.PAUSED,
                            DistributionCampaignState.ACTIVE,
                            Instant.ofEpochMilli(4_104L))
                    .toCompletableFuture()
                    .join());

            Page<CampaignRecipient> claimed = recipients
                    .claimPending(
                            campaign.campaignId(),
                            "completion-worker",
                            Instant.ofEpochMilli(4_200L),
                            Duration.ofSeconds(30),
                            10)
                    .toCompletableFuture()
                    .join();
            assertEquals(3, claimed.items().size());
            for (int index = 0; index < claimed.items().size(); index++) {
                UUID instanceId = seedInstance(runtime, definitionId, 4_210L + index);
                assertTrue(recipients.completeClaim(
                                campaign.campaignId(),
                                claimed.items().get(index).recipientKey(),
                                "completion-worker",
                                new LoreInstanceId(instanceId),
                                Instant.ofEpochMilli(4_300L + index))
                        .toCompletableFuture()
                        .join());
            }
            assertTrue(campaigns.transitionState(
                            campaign.campaignId(),
                            DistributionCampaignState.ACTIVE,
                            DistributionCampaignState.COMPLETED,
                            Instant.ofEpochMilli(4_400L))
                    .toCompletableFuture()
                    .join());
            assertEquals(
                    DistributionCampaignState.COMPLETED,
                    campaigns.findById(campaign.campaignId())
                            .toCompletableFuture()
                            .join()
                            .orElseThrow()
                            .state());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void expiredReservationMovesToReviewAfterRestart() {
        Path database = temporaryDirectory.resolve("restart.db");
        UUID campaignId;
        CampaignRecipientKey recipientKey;
        SQLiteStorageRuntime firstRuntime = start(database);
        try {
            UUID definitionId = seedDefinition(firstRuntime);
            SQLiteDistributionCampaignRepository campaigns =
                    new SQLiteDistributionCampaignRepository(firstRuntime);
            SQLiteDistributionRecipientRepository recipients =
                    new SQLiteDistributionRecipientRepository(firstRuntime);
            DistributionCampaign campaign = campaign(definitionId, "sha256:restart", 5_000L);
            campaignId = campaign.campaignId();
            CampaignRecipient recipient = knownRecipient(campaignId, 0, 5_010L);
            recipientKey = recipient.recipientKey();
            campaigns.create(campaign).toCompletableFuture().join();
            recipients.insertBatch(campaignId, List.of(recipient)).toCompletableFuture().join();
            campaigns.transitionState(
                            campaignId,
                            DistributionCampaignState.DRAFT,
                            DistributionCampaignState.ACTIVE,
                            Instant.ofEpochMilli(5_100L))
                    .toCompletableFuture()
                    .join();
            recipients.claimPending(
                            campaignId,
                            "pre-restart-worker",
                            Instant.ofEpochMilli(5_200L),
                            Duration.ofMillis(10L),
                            10)
                    .toCompletableFuture()
                    .join();
        } finally {
            firstRuntime.close(Duration.ofSeconds(5));
        }

        SQLiteStorageRuntime secondRuntime = start(database);
        try {
            SQLiteDistributionRecipientRepository recipients =
                    new SQLiteDistributionRecipientRepository(secondRuntime);
            assertEquals(
                    1,
                    recipients.moveExpiredClaimsToReview(
                                    Instant.ofEpochMilli(5_211L), 10)
                            .toCompletableFuture()
                            .join());
            CampaignRecipient recovered = recipients
                    .find(campaignId, recipientKey)
                    .toCompletableFuture()
                    .join()
                    .orElseThrow();
            assertEquals(CampaignRecipientState.REVIEW_REQUIRED, recovered.state());
            assertEquals(1, recovered.attemptCount());
        } finally {
            secondRuntime.close(Duration.ofSeconds(5));
        }
    }

    private static DistributionCampaign campaign(
            UUID definitionId, String sourceFingerprint, long createdAt) {
        return new DistributionCampaign(
                UUID.randomUUID(),
                sourceFingerprint,
                sourceFingerprint + ".yml",
                sourceFingerprint,
                new LoreDefinitionId(definitionId),
                DistributionCampaignState.DRAFT,
                createdAt,
                createdAt,
                null);
    }

    private static CampaignRecipient knownRecipient(
            UUID campaignId, int snapshotIndex, long createdAt) {
        UUID playerId = UUID.randomUUID();
        return CampaignRecipient.knownPlayer(
                campaignId, snapshotIndex, playerId, playerId.toString(), createdAt);
    }

    private static UUID seedDefinition(SQLiteStorageRuntime runtime) {
        UUID definitionId = UUID.randomUUID();
        runtime.execute(connection -> {
                    try (PreparedStatement definition = connection.prepareStatement(
                                    "INSERT INTO lore_definitions(definition_id, lookup_key, "
                                            + "display_name, current_revision, created_at, deleted_at) "
                                            + "VALUES (?, ?, ?, 1, 1, NULL)");
                            PreparedStatement revision = connection.prepareStatement(
                                    "INSERT INTO lore_definition_revisions(definition_id, "
                                            + "revision, codec_version, template_blob, created_at) "
                                            + "VALUES (?, 1, 1, ?, 1)")) {
                        definition.setString(1, definitionId.toString());
                        definition.setString(2, "definition-" + definitionId);
                        definition.setString(3, "Definition " + definitionId);
                        definition.executeUpdate();
                        revision.setString(1, definitionId.toString());
                        revision.setBytes(2, new byte[] {1});
                        revision.executeUpdate();
                        return null;
                    }
                })
                .toCompletableFuture()
                .join();
        return definitionId;
    }

    private static UUID seedInstance(
            SQLiteStorageRuntime runtime, UUID definitionId, long createdAt) {
        UUID instanceId = UUID.randomUUID();
        runtime.execute(connection -> {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "INSERT INTO lore_instances(instance_id, definition_id, "
                                    + "applied_revision, desired_revision, lifecycle_state, "
                                    + "created_at, terminal_at) "
                                    + "VALUES (?, ?, 1, 1, 'ACTIVE', ?, NULL)")) {
                        statement.setString(1, instanceId.toString());
                        statement.setString(2, definitionId.toString());
                        statement.setLong(3, createdAt);
                        statement.executeUpdate();
                        return null;
                    }
                })
                .toCompletableFuture()
                .join();
        return instanceId;
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
