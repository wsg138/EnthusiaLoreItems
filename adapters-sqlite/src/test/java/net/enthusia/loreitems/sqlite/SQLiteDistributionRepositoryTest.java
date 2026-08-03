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
import java.util.stream.IntStream;
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
    private static final Duration CLAIM_LEASE = Duration.ofSeconds(30);

    @TempDir
    Path temporaryDirectory;

    @Test
    void sourceAndRecipientSnapshotIdentityRemainImmutable() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("immutable.db"));
        try {
            DistributionFixture fixture = fixture(runtime, "sha256:source-a", 1_000L);
            assertDuplicateSourceRejected(fixture);
            CampaignRecipient recipient = insertOriginalRecipient(fixture);
            assertOriginalValueImmutable(runtime, fixture.campaign(), recipient);
            activate(fixture, 1_100L);
            assertLateSnapshotInsertRejected(fixture);
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void unresolvedNamesBindCaseInsensitivelyWithBoundedPagesAndCounts() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("binding.db"));
        try {
            DistributionFixture fixture = fixture(runtime, "sha256:binding", 2_000L);
            BindingScenario scenario = insertBindingSnapshot(fixture);
            activate(fixture, 2_100L);
            assertBoundedPages(fixture);
            assertUnresolvedLookup(fixture, scenario.lookup());
            assertBindingAndCounts(fixture, scenario);
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void claimsAreFencedAndCancellationPreservesDeliveredOrInFlightRecipients() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("cancel.db"));
        try {
            DistributionFixture fixture = fixture(runtime, "sha256:cancel", 3_000L);
            insertKnownSnapshot(fixture, 3_010L);
            activate(fixture, 3_100L);
            CampaignRecipient reserved = claimOne(fixture, "worker-a", 3_200L);
            assertWrongWorkerCannotRelease(fixture, reserved);
            cancelAndAssertPendingRecipients(fixture);
            completeReservedRecipient(runtime, fixture, reserved);
            assertCancellationCounts(fixture);
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void campaignTransitionsRequireCompleteSnapshotAndDeliveredRecipients() {
        SQLiteStorageRuntime runtime = start(temporaryDirectory.resolve("transitions.db"));
        try {
            DistributionFixture fixture = fixture(runtime, "sha256:transitions", 4_000L);
            assertIncompleteSnapshotCannotActivate(fixture);
            completeSnapshotAndExercisePause(fixture);
            deliverAll(runtime, fixture);
            assertCompleted(fixture);
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void expiredReservationMovesToReviewAfterRestart() {
        Path database = temporaryDirectory.resolve("restart.db");
        ReservedRecipient reserved = reserveBeforeRestart(database);
        assertRecoveredAfterRestart(database, reserved);
    }

    private static DistributionFixture fixture(
            SQLiteStorageRuntime runtime, String fingerprint, long createdAt) {
        UUID definitionId = seedDefinition(runtime);
        SQLiteDistributionCampaignRepository campaigns =
                new SQLiteDistributionCampaignRepository(runtime);
        SQLiteDistributionRecipientRepository recipients =
                new SQLiteDistributionRecipientRepository(runtime);
        DistributionCampaign campaign = campaign(definitionId, fingerprint, createdAt);
        campaigns.create(campaign).toCompletableFuture().join();
        return new DistributionFixture(definitionId, campaign, campaigns, recipients);
    }

    private static void assertDuplicateSourceRejected(DistributionFixture fixture) {
        DistributionCampaign duplicate = new DistributionCampaign(
                UUID.randomUUID(),
                "SHA256:SOURCE-A",
                "copied.yml",
                "Copied",
                new LoreDefinitionId(fixture.definitionId()),
                DistributionCampaignState.DRAFT,
                1_001L,
                1_001L,
                null);
        assertThrows(
                CompletionException.class,
                () -> fixture.campaigns().create(duplicate).toCompletableFuture().join());
    }

    private static CampaignRecipient insertOriginalRecipient(DistributionFixture fixture) {
        CampaignRecipient recipient = CampaignRecipient.unresolvedName(
                fixture.campaign().campaignId(), 0, "*OriginalName", 1_010L);
        fixture.recipients().insertBatch(fixture.campaign().campaignId(), List.of(recipient))
                .toCompletableFuture().join();
        return recipient;
    }

    private static void assertOriginalValueImmutable(
            SQLiteStorageRuntime runtime,
            DistributionCampaign campaign,
            CampaignRecipient recipient) {
        assertThrows(CompletionException.class, () -> runtime.execute(connection -> {
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
    }

    private static void assertLateSnapshotInsertRejected(DistributionFixture fixture) {
        DistributionCampaign campaign = fixture.campaign();
        assertThrows(CompletionException.class, () -> fixture.recipients().insertBatch(
                        campaign.campaignId(),
                        List.of(CampaignRecipient.unresolvedName(
                                campaign.campaignId(), 1, "LateAddition", 1_101L)))
                .toCompletableFuture().join());
    }

    private static BindingScenario insertBindingSnapshot(DistributionFixture fixture) {
        UUID campaignId = fixture.campaign().campaignId();
        UUID knownPlayer = UUID.randomUUID();
        CampaignRecipient floodgate =
                CampaignRecipient.unresolvedName(campaignId, 0, "*BedRockUser", 2_010L);
        CampaignRecipient alias =
                CampaignRecipient.unresolvedName(campaignId, 1, "OtherAlias", 2_011L);
        CampaignRecipient known = CampaignRecipient.knownPlayer(
                campaignId, 2, knownPlayer, knownPlayer.toString(), 2_012L);
        fixture.recipients().insertBatch(campaignId, List.of(floodgate, alias, known))
                .toCompletableFuture().join();
        return new BindingScenario(
                alias, CampaignRecipientKey.forUnresolvedName("*BEDROCKUSER"));
    }

    private static void assertBoundedPages(DistributionFixture fixture) {
        UUID campaignId = fixture.campaign().campaignId();
        Page<CampaignRecipient> firstPage = fixture.recipients()
                .listByCampaign(campaignId, PageRequest.first(2)).toCompletableFuture().join();
        Page<CampaignRecipient> secondPage = fixture.recipients()
                .listByCampaign(campaignId, PageRequest.first(2).next())
                .toCompletableFuture().join();
        assertEquals(2, firstPage.items().size());
        assertTrue(firstPage.hasMore());
        assertEquals(1, secondPage.items().size());
        assertFalse(secondPage.hasMore());
    }

    private static void assertUnresolvedLookup(
            DistributionFixture fixture, CampaignRecipientKey lookup) {
        Page<CampaignRecipient> unresolved = fixture.recipients()
                .listUnresolvedByKey(lookup, PageRequest.first(10))
                .toCompletableFuture().join();
        assertEquals(1, unresolved.items().size());
        assertEquals("*BedRockUser", unresolved.items().getFirst().originalValue());
    }

    private static void assertBindingAndCounts(
            DistributionFixture fixture, BindingScenario scenario) {
        UUID campaignId = fixture.campaign().campaignId();
        UUID joinedPlayer = UUID.randomUUID();
        assertTrue(fixture.recipients().bindUnresolvedName(
                        campaignId, scenario.lookup(), joinedPlayer, Instant.ofEpochMilli(2_200L))
                .toCompletableFuture().join());
        assertFalse(fixture.recipients().bindUnresolvedName(
                        campaignId,
                        scenario.alias().recipientKey(),
                        joinedPlayer,
                        Instant.ofEpochMilli(2_201L))
                .toCompletableFuture().join());
        CampaignRecipient rebound = fixture.recipients().find(campaignId, scenario.lookup())
                .toCompletableFuture().join().orElseThrow();
        CampaignRecipientCounts counts = fixture.recipients().countByState(campaignId)
                .toCompletableFuture().join();
        assertEquals(joinedPlayer, rebound.playerId());
        assertEquals("name:*bedrockuser", rebound.recipientKey().value());
        assertEquals("*BedRockUser", rebound.originalValue());
        assertEquals(1L, counts.pendingName());
        assertEquals(2L, counts.pendingOffline());
        assertEquals(3L, counts.total());
    }

    private static void insertKnownSnapshot(DistributionFixture fixture, long createdAt) {
        UUID campaignId = fixture.campaign().campaignId();
        List<CampaignRecipient> snapshot = List.of(
                knownRecipient(campaignId, 0, createdAt),
                knownRecipient(campaignId, 1, createdAt + 1),
                knownRecipient(campaignId, 2, createdAt + 2));
        fixture.recipients().insertBatch(campaignId, snapshot).toCompletableFuture().join();
    }

    private static CampaignRecipient claimOne(
            DistributionFixture fixture, String worker, long now) {
        Page<CampaignRecipient> claimed = fixture.recipients().claimPending(
                        fixture.campaign().campaignId(),
                        worker,
                        Instant.ofEpochMilli(now),
                        CLAIM_LEASE,
                        1)
                .toCompletableFuture().join();
        assertEquals(1, claimed.items().size());
        return claimed.items().getFirst();
    }

    private static void assertWrongWorkerCannotRelease(
            DistributionFixture fixture, CampaignRecipient reserved) {
        assertFalse(fixture.recipients().releaseClaim(
                        fixture.campaign().campaignId(),
                        reserved.recipientKey(),
                        CampaignRecipientState.PENDING_SPACE,
                        "worker-b",
                        Instant.ofEpochMilli(3_201L),
                        Instant.ofEpochMilli(4_000L))
                .toCompletableFuture().join());
    }

    private static void cancelAndAssertPendingRecipients(DistributionFixture fixture) {
        UUID campaignId = fixture.campaign().campaignId();
        CampaignCancellationResult cancellation = fixture.campaigns().cancel(
                        campaignId,
                        DistributionCampaignState.ACTIVE,
                        Instant.ofEpochMilli(3_300L))
                .toCompletableFuture().join();
        assertTrue(cancellation.cancelled());
        assertEquals(2, cancellation.recipientsCancelled());
        assertTrue(fixture.recipients().claimPending(
                        campaignId,
                        "worker-after-cancel",
                        Instant.ofEpochMilli(3_301L),
                        CLAIM_LEASE,
                        10)
                .toCompletableFuture().join().items().isEmpty());
    }

    private static void completeReservedRecipient(
            SQLiteStorageRuntime runtime,
            DistributionFixture fixture,
            CampaignRecipient reserved) {
        UUID instanceId = seedInstance(runtime, fixture.definitionId(), 3_250L);
        UUID campaignId = fixture.campaign().campaignId();
        LoreInstanceId loreInstanceId = new LoreInstanceId(instanceId);
        assertFalse(fixture.recipients().completeClaim(
                        campaignId,
                        reserved.recipientKey(),
                        "worker-b",
                        loreInstanceId,
                        Instant.ofEpochMilli(3_400L))
                .toCompletableFuture().join());
        assertTrue(fixture.recipients().completeClaim(
                        campaignId,
                        reserved.recipientKey(),
                        "worker-a",
                        loreInstanceId,
                        Instant.ofEpochMilli(3_400L))
                .toCompletableFuture().join());
    }

    private static void assertCancellationCounts(DistributionFixture fixture) {
        CampaignRecipientCounts counts = fixture.recipients()
                .countByState(fixture.campaign().campaignId()).toCompletableFuture().join();
        assertEquals(1L, counts.delivered());
        assertEquals(2L, counts.cancelled());
        assertEquals(3L, counts.total());
    }

    private static void assertIncompleteSnapshotCannotActivate(DistributionFixture fixture) {
        UUID campaignId = fixture.campaign().campaignId();
        fixture.recipients().insertBatch(
                        campaignId,
                        List.of(
                                knownRecipient(campaignId, 0, 4_010L),
                                knownRecipient(campaignId, 2, 4_012L)))
                .toCompletableFuture().join();
        assertFalse(transition(
                fixture, DistributionCampaignState.DRAFT, DistributionCampaignState.ACTIVE, 4_100L));
    }

    private static void completeSnapshotAndExercisePause(DistributionFixture fixture) {
        UUID campaignId = fixture.campaign().campaignId();
        fixture.recipients().insertBatch(
                        campaignId, List.of(knownRecipient(campaignId, 1, 4_011L)))
                .toCompletableFuture().join();
        assertTrue(transition(
                fixture, DistributionCampaignState.DRAFT, DistributionCampaignState.ACTIVE, 4_101L));
        assertTrue(transition(
                fixture, DistributionCampaignState.ACTIVE, DistributionCampaignState.PAUSED, 4_102L));
        assertFalse(transition(
                fixture, DistributionCampaignState.PAUSED, DistributionCampaignState.COMPLETED, 4_103L));
        assertTrue(transition(
                fixture, DistributionCampaignState.PAUSED, DistributionCampaignState.ACTIVE, 4_104L));
    }

    private static boolean transition(
            DistributionFixture fixture,
            DistributionCampaignState expected,
            DistributionCampaignState target,
            long now) {
        return fixture.campaigns().transitionState(
                        fixture.campaign().campaignId(), expected, target, Instant.ofEpochMilli(now))
                .toCompletableFuture().join();
    }

    private static void deliverAll(SQLiteStorageRuntime runtime, DistributionFixture fixture) {
        Page<CampaignRecipient> claimed = fixture.recipients().claimPending(
                        fixture.campaign().campaignId(),
                        "completion-worker",
                        Instant.ofEpochMilli(4_200L),
                        CLAIM_LEASE,
                        10)
                .toCompletableFuture().join();
        assertEquals(3, claimed.items().size());
        List<LoreInstanceId> instances = seedInstances(
                runtime, fixture.definitionId(), claimed.items().size(), 4_210L);
        for (int index = 0; index < claimed.items().size(); index++) {
            assertTrue(fixture.recipients().completeClaim(
                            fixture.campaign().campaignId(),
                            claimed.items().get(index).recipientKey(),
                            "completion-worker",
                            instances.get(index),
                            Instant.ofEpochMilli(4_300L + index))
                    .toCompletableFuture().join());
        }
    }

    private static List<LoreInstanceId> seedInstances(
            SQLiteStorageRuntime runtime, UUID definitionId, int count, long createdAt) {
        return IntStream.range(0, count)
                .mapToObj(index -> new LoreInstanceId(
                        seedInstance(runtime, definitionId, createdAt + index)))
                .toList();
    }

    private static void assertCompleted(DistributionFixture fixture) {
        assertTrue(transition(
                fixture,
                DistributionCampaignState.ACTIVE,
                DistributionCampaignState.COMPLETED,
                4_400L));
        assertEquals(
                DistributionCampaignState.COMPLETED,
                fixture.campaigns().findById(fixture.campaign().campaignId())
                        .toCompletableFuture().join().orElseThrow().state());
    }

    private static ReservedRecipient reserveBeforeRestart(Path database) {
        SQLiteStorageRuntime runtime = start(database);
        try {
            DistributionFixture fixture = fixture(runtime, "sha256:restart", 5_000L);
            UUID campaignId = fixture.campaign().campaignId();
            CampaignRecipient recipient = knownRecipient(campaignId, 0, 5_010L);
            fixture.recipients().insertBatch(campaignId, List.of(recipient))
                    .toCompletableFuture().join();
            activate(fixture, 5_100L);
            fixture.recipients().claimPending(
                            campaignId,
                            "pre-restart-worker",
                            Instant.ofEpochMilli(5_200L),
                            Duration.ofMillis(10L),
                            10)
                    .toCompletableFuture().join();
            return new ReservedRecipient(campaignId, recipient.recipientKey());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private static void assertRecoveredAfterRestart(Path database, ReservedRecipient reserved) {
        SQLiteStorageRuntime runtime = start(database);
        try {
            SQLiteDistributionRecipientRepository recipients =
                    new SQLiteDistributionRecipientRepository(runtime);
            assertEquals(1, recipients.moveExpiredClaimsToReview(
                            Instant.ofEpochMilli(5_211L), 10)
                    .toCompletableFuture().join());
            CampaignRecipient recovered = recipients.find(
                            reserved.campaignId(), reserved.recipientKey())
                    .toCompletableFuture().join().orElseThrow();
            assertEquals(CampaignRecipientState.REVIEW_REQUIRED, recovered.state());
            assertEquals(1, recovered.attemptCount());
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
    }

    private static void activate(DistributionFixture fixture, long now) {
        assertTrue(transition(
                fixture, DistributionCampaignState.DRAFT, DistributionCampaignState.ACTIVE, now));
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
                .toCompletableFuture().join();
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
                .toCompletableFuture().join();
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

    private record DistributionFixture(
            UUID definitionId,
            DistributionCampaign campaign,
            SQLiteDistributionCampaignRepository campaigns,
            SQLiteDistributionRecipientRepository recipients) {
    }

    private record BindingScenario(
            CampaignRecipient alias, CampaignRecipientKey lookup) {
    }

    private record ReservedRecipient(
            UUID campaignId, CampaignRecipientKey recipientKey) {
    }
}
