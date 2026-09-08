package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import net.enthusia.loreitems.application.DistributionCampaignStartResult;
import net.enthusia.loreitems.domain.CampaignRecipientState;
import net.enthusia.loreitems.domain.DefinitionKey;
import net.enthusia.loreitems.domain.DistributionCampaignState;
import net.enthusia.loreitems.domain.LoreDefinition;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PaperDistributionCampaignCoordinatorTest {
    private static final UUID CAMPAIGN_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID EXPLICIT_PLAYER = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID DEFINITION_ID = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final long NOW = 1_800_000_000_000L;

    @TempDir
    Path temporaryDirectory;

    @Test
    void previewPinsSourceDefinitionRevisionAndRecipientSnapshot() throws Exception {
        writeSource(defaultSource());
        LoreDefinition definition = definition();
        PaperDistributionCampaignCoordinator coordinator = coordinator(
                definition,
                request -> CompletableFuture.completedFuture(new DistributionCampaignStartResult(
                        DistributionCampaignStartResult.Status.STARTED,
                        request.campaign().campaignId())));

        DistributionCampaignPreview preview = coordinator.preview(
                        "launch.yml",
                        definition.key(),
                        "PLAYER",
                        "operator")
                .toCompletableFuture()
                .join()
                .orElseThrow();

        assertEquals(CAMPAIGN_ID, preview.campaignId());
        assertEquals(DistributionCampaignState.DRAFT, preview.startRequest().campaign().state());
        assertEquals(definition.id(), preview.startRequest().campaign().definitionId());
        assertEquals(definition.currentRevision(), preview.startRequest().campaign().definitionRevision());
        assertEquals(3, preview.startRequest().recipients().size());
        assertEquals(CampaignRecipientState.UNRESOLVED, preview.startRequest().recipients().get(0).state());
        assertEquals(CampaignRecipientState.UNRESOLVED, preview.startRequest().recipients().get(1).state());
        assertEquals(CampaignRecipientState.QUEUED_OFFLINE, preview.startRequest().recipients().get(2).state());
        assertEquals("*BedrockPlayer", preview.startRequest().recipients().get(1).originalValue());
        assertEquals(EXPLICIT_PLAYER, preview.startRequest().recipients().get(2).playerId());
    }

    @Test
    void cachedIdentityResolutionUsesBoundedServerExecutorPasses() throws Exception {
        int recipientCount = 300;
        writeSource(namedRecipientsSource(recipientCount));
        LoreDefinition definition = definition();
        AtomicInteger lookups = new AtomicInteger();
        ArrayDeque<Runnable> serverTasks = new ArrayDeque<>();
        PaperDistributionCampaignCoordinator coordinator = new PaperDistributionCampaignCoordinator(
                new PaperGroupFileCatalog(temporaryDirectory),
                key -> CompletableFuture.completedFuture(
                        key.equals(definition.key()) ? Optional.of(definition) : Optional.empty()),
                request -> CompletableFuture.completedFuture(new DistributionCampaignStartResult(
                        DistributionCampaignStartResult.Status.STARTED,
                        request.campaign().campaignId())),
                name -> {
                    lookups.incrementAndGet();
                    return Optional.empty();
                },
                serverTasks::addLast,
                Runnable::run,
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC),
                () -> CAMPAIGN_ID);

        CompletableFuture<Optional<DistributionCampaignPreview>> preview = coordinator.preview(
                        "launch.yml",
                        definition.key(),
                        "PLAYER",
                        "operator")
                .toCompletableFuture();

        assertFalse(preview.isDone());
        assertEquals(1, serverTasks.size());
        serverTasks.removeFirst().run();
        assertTrue(lookups.get() > 0);
        assertTrue(lookups.get() < recipientCount);
        assertFalse(preview.isDone());
        while (!serverTasks.isEmpty()) {
            serverTasks.removeFirst().run();
        }
        assertEquals(recipientCount, lookups.get());
        assertTrue(preview.isDone());
        assertEquals(recipientCount, preview.join().orElseThrow().startRequest().recipients().size());
    }

    @Test
    void confirmationCommitsBeforeMovingSourceToActiveMarker() throws Exception {
        writeSource(defaultSource());
        Path source = sourcePath();
        PaperDistributionCampaignCoordinator coordinator = coordinator(
                definition(),
                request -> {
                    assertTrue(Files.isRegularFile(source), "source must exist when DB start runs");
                    return CompletableFuture.completedFuture(new DistributionCampaignStartResult(
                            DistributionCampaignStartResult.Status.STARTED,
                            request.campaign().campaignId()));
                });
        DistributionCampaignPreview preview = preview(coordinator);

        DistributionCampaignConfirmationResult result = coordinator.confirm(preview)
                .toCompletableFuture()
                .join();

        assertEquals(DistributionCampaignConfirmationResult.Status.STARTED, result.status());
        assertFalse(Files.exists(source));
        assertNotNull(result.markerPath());
        assertTrue(Files.isRegularFile(result.markerPath()));
    }

    @Test
    void changedSourceFailsClosedBeforeDatabaseStart() throws Exception {
        writeSource(defaultSource());
        AtomicInteger starts = new AtomicInteger();
        PaperDistributionCampaignCoordinator coordinator = coordinator(
                definition(),
                request -> {
                    starts.incrementAndGet();
                    return CompletableFuture.completedFuture(new DistributionCampaignStartResult(
                            DistributionCampaignStartResult.Status.STARTED,
                            request.campaign().campaignId()));
                });
        DistributionCampaignPreview preview = preview(coordinator);
        Files.writeString(sourcePath(), "# changed after preview\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.APPEND);

        DistributionCampaignConfirmationResult result = coordinator.confirm(preview)
                .toCompletableFuture()
                .join();

        assertEquals(DistributionCampaignConfirmationResult.Status.SOURCE_CHANGED, result.status());
        assertEquals(0, starts.get());
        assertTrue(Files.isRegularFile(sourcePath()));
    }

    @Test
    void databaseFailureLeavesDiscoverableSourceInPlace() throws Exception {
        writeSource(defaultSource());
        PaperDistributionCampaignCoordinator coordinator = coordinator(
                definition(),
                request -> CompletableFuture.failedFuture(new IllegalStateException("database unavailable")));
        DistributionCampaignPreview preview = preview(coordinator);

        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> coordinator.confirm(preview).toCompletableFuture().join());

        assertTrue(failure.getCause() instanceof IllegalStateException);
        assertTrue(Files.isRegularFile(sourcePath()));
    }

    @Test
    void postCommitMarkerFailureDoesNotPretendDatabaseStartRolledBack() throws Exception {
        writeSource(defaultSource());
        PaperDistributionCampaignCoordinator coordinator = coordinator(
                definition(),
                request -> {
                    try {
                        Files.delete(sourcePath());
                    } catch (IOException exception) {
                        return CompletableFuture.failedFuture(exception);
                    }
                    return CompletableFuture.completedFuture(new DistributionCampaignStartResult(
                            DistributionCampaignStartResult.Status.STARTED,
                            request.campaign().campaignId()));
                });
        DistributionCampaignPreview preview = preview(coordinator);

        DistributionCampaignConfirmationResult result = coordinator.confirm(preview)
                .toCompletableFuture()
                .join();

        assertEquals(
                DistributionCampaignConfirmationResult.Status.STARTED_MARKER_REPAIR_REQUIRED,
                result.status());
        assertEquals(CAMPAIGN_ID, result.campaignId());
        assertTrue(result.detail().contains("durable"));
    }

    @Test
    void replayRefusalDoesNotMoveReintroducedSourceAsNewCampaign() throws Exception {
        writeSource(defaultSource());
        UUID existingCampaign = UUID.fromString("40000000-0000-0000-0000-000000000004");
        PaperDistributionCampaignCoordinator coordinator = coordinator(
                definition(),
                request -> CompletableFuture.completedFuture(new DistributionCampaignStartResult(
                        DistributionCampaignStartResult.Status.SOURCE_ALREADY_USED,
                        existingCampaign)));
        DistributionCampaignPreview preview = preview(coordinator);

        DistributionCampaignConfirmationResult result = coordinator.confirm(preview)
                .toCompletableFuture()
                .join();

        assertEquals(DistributionCampaignConfirmationResult.Status.SOURCE_ALREADY_USED, result.status());
        assertEquals(existingCampaign, result.campaignId());
        assertTrue(Files.isRegularFile(sourcePath()));
    }

    private PaperDistributionCampaignCoordinator coordinator(
            LoreDefinition definition,
            net.enthusia.loreitems.application.DistributionCampaignStartRepository startRepository) {
        return new PaperDistributionCampaignCoordinator(
                new PaperGroupFileCatalog(temporaryDirectory),
                key -> CompletableFuture.completedFuture(
                        key.equals(definition.key()) ? Optional.of(definition) : Optional.empty()),
                startRepository,
                Runnable::run,
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC),
                () -> CAMPAIGN_ID);
    }

    private DistributionCampaignPreview preview(PaperDistributionCampaignCoordinator coordinator) {
        return coordinator.preview("launch.yml", definition().key(), "PLAYER", "operator")
                .toCompletableFuture()
                .join()
                .orElseThrow();
    }

    private static LoreDefinition definition() {
        return new LoreDefinition(
                new LoreDefinitionId(DEFINITION_ID),
                new DefinitionKey("founder_blade"),
                "Founder Blade",
                new TemplateRevision(7L),
                NOW - 1_000L,
                null);
    }

    private static String defaultSource() {
        return """
                display-name: Launch group
                players:
                  - JavaPlayer
                  - '*BedrockPlayer'
                  - %s
                """.formatted(EXPLICIT_PLAYER);
    }

    private static String namedRecipientsSource(int recipientCount) {
        StringBuilder source = new StringBuilder("display-name: Launch group\nplayers:\n");
        for (int index = 0; index < recipientCount; index++) {
            source.append("  - Player").append(index).append('\n');
        }
        return source.toString();
    }

    private void writeSource(String content) throws IOException {
        Files.createDirectories(temporaryDirectory.resolve("groups"));
        Files.writeString(sourcePath(), content, StandardCharsets.UTF_8);
    }

    private Path sourcePath() {
        return temporaryDirectory.resolve("groups/launch.yml");
    }
}
