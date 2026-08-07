package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import net.enthusia.loreitems.application.DistributionCampaignStartRequest;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.PreparedDistributionDelivery;
import net.enthusia.loreitems.domain.CampaignRecipient;
import net.enthusia.loreitems.domain.CampaignRecipientState;
import net.enthusia.loreitems.domain.DistributionCampaign;
import net.enthusia.loreitems.domain.DistributionCampaignState;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SQLiteDistributionEndToEndTest {
    private static final long CREATED_AT = 1_000L;
    private static final Instant CLAIM_AT = Instant.ofEpochMilli(2_000L);
    private static final Duration LEASE = Duration.ofMinutes(1);

    @TempDir
    Path temporaryDirectory;

    @Test
    void samePlayerReceivesOneIndependentInstanceFromEachCampaign() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("multi-campaign.db"));
        try {
            UUID definitionId = seedDefinition(runtime);
            UUID playerId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
            DistributionCampaign first = startCampaign(
                    runtime, definitionId, playerId, "first.yml", "sha256:first", CREATED_AT);
            DistributionCampaign second = startCampaign(
                    runtime, definitionId, playerId, "second.yml", "sha256:second", CREATED_AT + 1);
            SQLiteDistributionDeliveryRepository delivery =
                    new SQLiteDistributionDeliveryRepository(runtime);

            List<CampaignRecipient> claims = delivery.claimPending("worker", CLAIM_AT, LEASE, 10)
                    .toCompletableFuture().join().items();
            assertEquals(2, claims.size());
            PreparedDistributionDelivery firstPrepared = prepareFor(delivery, claims, first.campaignId());
            PreparedDistributionDelivery secondPrepared = prepareFor(delivery, claims, second.campaignId());
            assertNotEquals(firstPrepared.instanceId(), secondPrepared.instanceId());

            assertTrue(delivery.completePrepared(
                            firstPrepared, 0, "a".repeat(64), CLAIM_AT.plusMillis(2))
                    .toCompletableFuture().join());
            assertTrue(delivery.completePrepared(
                            secondPrepared, 1, "b".repeat(64), CLAIM_AT.plusMillis(3))
                    .toCompletableFuture().join());
            assertFalse(delivery.completePrepared(
                            firstPrepared, 0, "a".repeat(64), CLAIM_AT.plusMillis(4))
                    .toCompletableFuture().join());
            assertFalse(delivery.completePrepared(
                            secondPrepared, 1, "b".repeat(64), CLAIM_AT.plusMillis(5))
                    .toCompletableFuture().join());

            assertDelivered(runtime, first, firstPrepared);
            assertDelivered(runtime, second, secondPrepared);
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private static PreparedDistributionDelivery prepareFor(
            SQLiteDistributionDeliveryRepository delivery,
            List<CampaignRecipient> claims,
            UUID campaignId) {
        CampaignRecipient claim = claims.stream()
                .filter(recipient -> recipient.campaignId().equals(campaignId))
                .findFirst()
                .orElseThrow();
        return delivery.prepareClaimed(claim, CLAIM_AT.plusMillis(1))
                .toCompletableFuture().join().orElseThrow();
    }

    private static void assertDelivered(
            SQLiteStorageRuntime runtime,
            DistributionCampaign campaign,
            PreparedDistributionDelivery prepared) {
        CampaignRecipient stored = new SQLiteDistributionRecipientRepository(runtime)
                .listByCampaign(campaign.campaignId(), net.enthusia.loreitems.application.PageRequest.first(10))
                .toCompletableFuture().join().items().getFirst();
        assertEquals(CampaignRecipientState.DELIVERED, stored.state());
        assertEquals(prepared.instanceId(), stored.instanceId());
        assertEquals(
                DistributionCampaignState.COMPLETED,
                new SQLiteDistributionCampaignRepository(runtime)
                        .findById(campaign.campaignId())
                        .toCompletableFuture().join().orElseThrow().state());
    }

    private static DistributionCampaign startCampaign(
            SQLiteStorageRuntime runtime,
            UUID definitionId,
            UUID playerId,
            String sourceName,
            String fingerprint,
            long createdAt) {
        UUID campaignId = UUID.randomUUID();
        DistributionCampaign campaign = new DistributionCampaign(
                campaignId,
                fingerprint,
                sourceName,
                sourceName,
                new LoreDefinitionId(definitionId),
                new TemplateRevision(1),
                DistributionCampaignState.DRAFT,
                createdAt,
                createdAt,
                null);
        CampaignRecipient recipient = CampaignRecipient.knownPlayer(
                campaignId, 0, playerId, playerId.toString(), createdAt);
        new SQLiteDistributionCampaignStartRepository(runtime)
                .start(new DistributionCampaignStartRequest(
                        campaign, List.of(recipient), "PLAYER", "operator"))
                .toCompletableFuture().join();
        return campaign;
    }

    private static UUID seedDefinition(SQLiteStorageRuntime runtime) {
        UUID definitionId = UUID.randomUUID();
        runtime.execute(connection -> {
            try (PreparedStatement definition = connection.prepareStatement(
                    "INSERT INTO lore_definitions(definition_id, lookup_key, display_name, "
                            + "current_revision, created_at) VALUES (?, ?, 'Definition', 1, 1)")) {
                definition.setString(1, definitionId.toString());
                definition.setString(2, "definition-" + definitionId);
                definition.executeUpdate();
            }
            try (PreparedStatement revision = connection.prepareStatement(
                    "INSERT INTO lore_definition_revisions(definition_id, revision, codec_version, "
                            + "template_blob, created_at) VALUES (?, 1, 1, ?, 1)")) {
                revision.setString(1, definitionId.toString());
                revision.setBytes(2, new byte[] {1});
                revision.executeUpdate();
            }
            return null;
        }).toCompletableFuture().join();
        return definitionId;
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
