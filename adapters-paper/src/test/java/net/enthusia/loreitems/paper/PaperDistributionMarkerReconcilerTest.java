package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.domain.DistributionCampaign;
import net.enthusia.loreitems.domain.DistributionCampaignState;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PaperDistributionMarkerReconcilerTest {
    private static final long NOW = 1_800_000_000_000L;

    @TempDir
    Path temporaryDirectory;

    @Test
    void repairsActiveAndTerminalMarkersFromDurableCampaignState() throws Exception {
        PaperGroupFileCatalog catalog = new PaperGroupFileCatalog(temporaryDirectory);
        GroupFileDefinition activeSource = writeAndInspect(catalog, "active.yml", "Active");
        GroupFileDefinition completedSource = writeAndInspect(catalog, "completed.yml", "Completed");
        DistributionCampaign active = campaign(activeSource, DistributionCampaignState.ACTIVE);
        DistributionCampaign completed = campaign(completedSource, DistributionCampaignState.COMPLETED);
        PageRequest request = PageRequest.first(2);
        PaperDistributionMarkerReconciler reconciler = new PaperDistributionMarkerReconciler(
                catalog,
                ignored -> CompletableFuture.completedFuture(new Page<>(
                        List.of(active, completed), request.offset(), request.limit(), false)),
                Runnable::run);

        DistributionMarkerReconciliationPage result = reconciler.reconcile(request)
                .toCompletableFuture()
                .join();

        assertEquals(2, result.entries().size());
        assertEquals(
                DistributionMarkerReconciliationPage.Status.RECONCILED,
                result.entries().get(0).status());
        assertEquals(
                DistributionMarkerReconciliationPage.Status.RECONCILED,
                result.entries().get(1).status());
        assertTrue(Files.isRegularFile(result.entries().get(0).markerPath()));
        assertTrue(result.entries().get(0).markerPath().getFileName().toString().contains(".active-"));
        assertTrue(Files.isRegularFile(result.entries().get(1).markerPath()));
        assertTrue(result.entries().get(1).markerPath().startsWith(
                temporaryDirectory.resolve("groups/completed")));
    }

    @Test
    void reportsMissingSourceAndReturnsBoundedNextPage() {
        PaperGroupFileCatalog catalog = new PaperGroupFileCatalog(temporaryDirectory);
        DistributionCampaign missing = new DistributionCampaign(
                UUID.randomUUID(),
                "sha256:missing",
                "missing.yml",
                "Missing",
                LoreDefinitionId.random(),
                new TemplateRevision(1L),
                DistributionCampaignState.ACTIVE,
                NOW,
                NOW,
                null);
        PageRequest request = PageRequest.first(1);
        PaperDistributionMarkerReconciler reconciler = new PaperDistributionMarkerReconciler(
                catalog,
                ignored -> CompletableFuture.completedFuture(new Page<>(
                        List.of(missing), request.offset(), request.limit(), true)),
                Runnable::run);

        DistributionMarkerReconciliationPage result = reconciler.reconcile(request)
                .toCompletableFuture()
                .join();

        assertEquals(
                DistributionMarkerReconciliationPage.Status.MISSING_SOURCE,
                result.entries().getFirst().status());
        assertNotNull(result.nextPage());
        assertEquals(1, result.nextPage().offset());
        assertEquals(1, result.nextPage().limit());
    }

    private GroupFileDefinition writeAndInspect(
            PaperGroupFileCatalog catalog, String sourceName, String displayName) throws IOException {
        Files.createDirectories(temporaryDirectory.resolve("groups"));
        Files.writeString(
                temporaryDirectory.resolve("groups").resolve(sourceName),
                "display-name: " + displayName + "\nplayers:\n  - Player\n",
                StandardCharsets.UTF_8);
        return catalog.inspect(sourceName);
    }

    private static DistributionCampaign campaign(
            GroupFileDefinition source, DistributionCampaignState state) {
        Long terminalAt = state.terminal() ? NOW : null;
        return new DistributionCampaign(
                UUID.randomUUID(),
                source.sourceFingerprint(),
                source.sourceName(),
                source.displayName(),
                LoreDefinitionId.random(),
                new TemplateRevision(1L),
                state,
                NOW,
                NOW,
                terminalAt);
    }
}
