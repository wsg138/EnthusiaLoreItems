package net.enthusia.loreitems.paper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Function;
import net.enthusia.loreitems.application.DistributionCampaignRepository;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.domain.DistributionCampaign;

public final class PaperDistributionMarkerReconciler {
    private static final String YAML_SUFFIX = ".yml";
    private static final String ACTIVE_MARKER = ".active-";

    private final PaperGroupFileCatalog groupCatalog;
    private final Function<PageRequest, CompletionStage<Page<DistributionCampaign>>> campaignLookup;
    private final Executor blockingExecutor;

    public PaperDistributionMarkerReconciler(
            PaperGroupFileCatalog groupCatalog,
            DistributionCampaignRepository campaignRepository,
            Executor blockingExecutor) {
        this(
                groupCatalog,
                Objects.requireNonNull(campaignRepository, "campaignRepository")::list,
                blockingExecutor);
    }

    PaperDistributionMarkerReconciler(
            PaperGroupFileCatalog groupCatalog,
            Function<PageRequest, CompletionStage<Page<DistributionCampaign>>> campaignLookup,
            Executor blockingExecutor) {
        this.groupCatalog = Objects.requireNonNull(groupCatalog, "groupCatalog");
        this.campaignLookup = Objects.requireNonNull(campaignLookup, "campaignLookup");
        this.blockingExecutor = Objects.requireNonNull(blockingExecutor, "blockingExecutor");
    }

    public CompletionStage<DistributionMarkerReconciliationPage> reconcile(PageRequest request) {
        Objects.requireNonNull(request, "request");
        return campaignLookup.apply(request).thenCompose(page -> CompletableFuture.supplyAsync(
                () -> reconcilePage(page),
                blockingExecutor));
    }

    private DistributionMarkerReconciliationPage reconcilePage(Page<DistributionCampaign> page) {
        List<DistributionMarkerReconciliationPage.Entry> entries = new ArrayList<>(page.items().size());
        for (DistributionCampaign campaign : page.items()) {
            entries.add(reconcileCampaign(campaign));
        }
        PageRequest next = page.hasMore() ? new PageRequest(page.offset() + page.limit(), page.limit()) : null;
        return new DistributionMarkerReconciliationPage(entries, next);
    }

    private DistributionMarkerReconciliationPage.Entry reconcileCampaign(DistributionCampaign campaign) {
        try {
            return switch (campaign.state()) {
                case DRAFT -> entry(
                        campaign,
                        DistributionMarkerReconciliationPage.Status.DRAFT_SKIPPED,
                        null,
                        "Draft campaign has no operational filesystem marker.");
                case ACTIVE, PAUSED -> verifyMarker(
                        campaign,
                        groupCatalog.repairActiveMarker(
                                campaign.sourceName(),
                                campaign.sourceFingerprint(),
                                campaign.campaignId()));
                case COMPLETED -> verifyMarker(campaign, reconcileCompleted(campaign));
                case CANCELLED -> verifyMarker(campaign, reconcileCancelled(campaign));
            };
        } catch (IOException | IllegalArgumentException exception) {
            return entry(
                    campaign,
                    DistributionMarkerReconciliationPage.Status.FAILED,
                    null,
                    safeMessage(exception));
        }
    }

    private Path reconcileCompleted(DistributionCampaign campaign) throws IOException {
        groupCatalog.repairActiveMarker(
                campaign.sourceName(), campaign.sourceFingerprint(), campaign.campaignId());
        Path marker = groupCatalog.moveToCompleted(campaign.sourceName(), campaign.campaignId());
        removeDuplicateActiveMarker(campaign, marker);
        return marker;
    }

    private Path reconcileCancelled(DistributionCampaign campaign) throws IOException {
        groupCatalog.repairActiveMarker(
                campaign.sourceName(), campaign.sourceFingerprint(), campaign.campaignId());
        Path marker = groupCatalog.moveToCancelled(campaign.sourceName(), campaign.campaignId());
        removeDuplicateActiveMarker(campaign, marker);
        return marker;
    }

    private static void removeDuplicateActiveMarker(
            DistributionCampaign campaign, Path terminalMarker) throws IOException {
        if (!Files.isRegularFile(terminalMarker, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(terminalMarker)) {
            return;
        }
        Path terminalDirectory = terminalMarker.getParent();
        Path groupsDirectory = terminalDirectory == null ? null : terminalDirectory.getParent();
        if (groupsDirectory == null) {
            throw new IOException("Terminal campaign marker is outside the groups directory layout");
        }
        String sourceName = campaign.sourceName();
        String stem = sourceName.substring(0, sourceName.length() - YAML_SUFFIX.length());
        Path activeMarker = groupsDirectory.resolve(
                stem + ACTIVE_MARKER + campaign.campaignId() + YAML_SUFFIX);
        if (!Files.exists(activeMarker, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(activeMarker)
                || !Files.isRegularFile(activeMarker, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Duplicate active campaign marker is not a safe regular file");
        }
        Files.delete(activeMarker);
    }

    private static DistributionMarkerReconciliationPage.Entry verifyMarker(
            DistributionCampaign campaign, Path markerPath) {
        if (!Files.isRegularFile(markerPath, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(markerPath)) {
            return entry(
                    campaign,
                    DistributionMarkerReconciliationPage.Status.MISSING_SOURCE,
                    null,
                    "Durable campaign state is authoritative, but no matching source marker is recoverable.");
        }
        return entry(
                campaign,
                DistributionMarkerReconciliationPage.Status.RECONCILED,
                markerPath,
                "Filesystem marker matches durable campaign state.");
    }

    private static DistributionMarkerReconciliationPage.Entry entry(
            DistributionCampaign campaign,
            DistributionMarkerReconciliationPage.Status status,
            Path markerPath,
            String detail) {
        return new DistributionMarkerReconciliationPage.Entry(
                campaign.campaignId(),
                campaign.state(),
                status,
                markerPath,
                detail);
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }
}
