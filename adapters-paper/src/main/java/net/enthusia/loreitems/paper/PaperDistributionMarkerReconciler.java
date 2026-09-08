package net.enthusia.loreitems.paper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
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
        requireMatchingMarker(campaign, marker);
        removeDuplicateActiveMarker(campaign, marker);
        return marker;
    }

    private Path reconcileCancelled(DistributionCampaign campaign) throws IOException {
        groupCatalog.repairActiveMarker(
                campaign.sourceName(), campaign.sourceFingerprint(), campaign.campaignId());
        Path marker = groupCatalog.moveToCancelled(campaign.sourceName(), campaign.campaignId());
        requireMatchingMarker(campaign, marker);
        removeDuplicateActiveMarker(campaign, marker);
        return marker;
    }

    private static void requireMatchingMarker(
            DistributionCampaign campaign, Path marker) throws IOException {
        if (!markerMatchesCampaign(campaign, marker)) {
            throw new IOException(
                    "Existing campaign marker does not match durable campaign source evidence");
        }
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
        try {
            if (!markerMatchesCampaign(campaign, markerPath)) {
                return entry(
                        campaign,
                        DistributionMarkerReconciliationPage.Status.FAILED,
                        markerPath,
                        "Filesystem marker is safe but does not match durable campaign source evidence.");
            }
        } catch (IOException exception) {
            return entry(
                    campaign,
                    DistributionMarkerReconciliationPage.Status.FAILED,
                    markerPath,
                    safeMessage(exception));
        }
        return entry(
                campaign,
                DistributionMarkerReconciliationPage.Status.RECONCILED,
                markerPath,
                "Filesystem marker matches durable campaign state.");
    }

    private static boolean markerMatchesCampaign(
            DistributionCampaign campaign, Path markerPath) throws IOException {
        byte[] markerBytes = Files.readAllBytes(markerPath);
        if (campaign.sourceFingerprint().equals(sourceFingerprint(campaign.sourceName(), markerBytes))) {
            return true;
        }
        String recovered = new String(markerBytes, StandardCharsets.UTF_8);
        return recovered.equals(recoveryMarkerContent(campaign));
    }

    private static String recoveryMarkerContent(DistributionCampaign campaign) {
        return "# Recovered by EnthusiaLoreItems from durable campaign state.\n"
                + "# This operator marker is not a reusable distribution source.\n"
                + "campaign-id: " + campaign.campaignId() + '\n'
                + "source-name: " + campaign.sourceName() + '\n'
                + "source-fingerprint: " + campaign.sourceFingerprint() + '\n';
    }

    private static String sourceFingerprint(String sourceName, byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(sourceName.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(bytes);
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Java runtime does not provide SHA-256", exception);
        }
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
