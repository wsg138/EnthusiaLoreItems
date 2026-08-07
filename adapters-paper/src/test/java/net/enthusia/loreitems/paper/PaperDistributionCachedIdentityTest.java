package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.enthusia.loreitems.application.DistributionCampaignStartResult;
import net.enthusia.loreitems.domain.CampaignRecipientState;
import net.enthusia.loreitems.domain.DefinitionKey;
import net.enthusia.loreitems.domain.LoreDefinition;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PaperDistributionCachedIdentityTest {
    private static final long NOW = 1_800_000_000_000L;

    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesJavaAndFloodgateNamesFromCacheWhileLeavingUnknownNamesUnresolved() throws Exception {
        Files.createDirectories(temporaryDirectory.resolve("groups"));
        Files.writeString(temporaryDirectory.resolve("groups/cache.yml"), """
                display-name: Cached identities
                players:
                  - JavaPlayer
                  - '*BedrockPlayer'
                  - FuturePlayer
                """);
        UUID javaId = UUID.fromString("10000000-0000-0000-0000-000000000001");
        UUID bedrockId = UUID.fromString("20000000-0000-0000-0000-000000000002");
        LoreDefinition definition = new LoreDefinition(
                LoreDefinitionId.random(),
                new DefinitionKey("cached_item"),
                "Cached Item",
                new TemplateRevision(3L),
                NOW,
                null);
        PaperDistributionCampaignCoordinator coordinator = new PaperDistributionCampaignCoordinator(
                new PaperGroupFileCatalog(temporaryDirectory),
                key -> CompletableFuture.completedFuture(Optional.of(definition)),
                request -> CompletableFuture.completedFuture(new DistributionCampaignStartResult(
                        DistributionCampaignStartResult.Status.STARTED,
                        request.campaign().campaignId())),
                name -> switch (name.toLowerCase(java.util.Locale.ROOT)) {
                    case "javaplayer" -> Optional.of(javaId);
                    case "*bedrockplayer" -> Optional.of(bedrockId);
                    default -> Optional.empty();
                },
                Runnable::run,
                Runnable::run,
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC),
                UUID::randomUUID);

        DistributionCampaignPreview preview = coordinator.preview(
                        "cache.yml", definition.key(), "PLAYER", "operator")
                .toCompletableFuture()
                .join()
                .orElseThrow();

        assertEquals(CampaignRecipientState.QUEUED_OFFLINE, preview.startRequest().recipients().get(0).state());
        assertEquals(javaId, preview.startRequest().recipients().get(0).playerId());
        assertEquals(CampaignRecipientState.QUEUED_OFFLINE, preview.startRequest().recipients().get(1).state());
        assertEquals(bedrockId, preview.startRequest().recipients().get(1).playerId());
        assertEquals("*BedrockPlayer", preview.startRequest().recipients().get(1).originalValue());
        assertEquals(CampaignRecipientState.UNRESOLVED, preview.startRequest().recipients().get(2).state());
    }
}
