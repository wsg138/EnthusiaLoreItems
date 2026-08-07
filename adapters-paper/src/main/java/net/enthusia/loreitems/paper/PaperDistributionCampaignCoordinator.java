package net.enthusia.loreitems.paper;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;
import net.enthusia.loreitems.application.DefinitionRepository;
import net.enthusia.loreitems.application.DistributionCampaignStartRepository;
import net.enthusia.loreitems.application.DistributionCampaignStartRequest;
import net.enthusia.loreitems.application.DistributionCampaignStartResult;
import net.enthusia.loreitems.domain.CampaignRecipient;
import net.enthusia.loreitems.domain.DefinitionKey;
import net.enthusia.loreitems.domain.DistributionCampaign;
import net.enthusia.loreitems.domain.DistributionCampaignState;
import net.enthusia.loreitems.domain.LoreDefinition;

public final class PaperDistributionCampaignCoordinator {
    private final PaperGroupFileCatalog groupCatalog;
    private final Function<DefinitionKey, CompletionStage<Optional<LoreDefinition>>> definitionLookup;
    private final DistributionCampaignStartRepository startRepository;
    private final Function<String, Optional<UUID>> cachedIdentityLookup;
    private final Executor serverExecutor;
    private final Executor blockingExecutor;
    private final Clock clock;
    private final Supplier<UUID> campaignIds;

    public PaperDistributionCampaignCoordinator(
            PaperGroupFileCatalog groupCatalog,
            DefinitionRepository definitionRepository,
            DistributionCampaignStartRepository startRepository,
            Executor blockingExecutor) {
        this(
                groupCatalog,
                Objects.requireNonNull(definitionRepository, "definitionRepository")::findActiveByKey,
                startRepository,
                ignored -> Optional.empty(),
                Runnable::run,
                blockingExecutor,
                Clock.systemUTC(),
                UUID::randomUUID);
    }

    public PaperDistributionCampaignCoordinator(
            PaperGroupFileCatalog groupCatalog,
            DefinitionRepository definitionRepository,
            DistributionCampaignStartRepository startRepository,
            PaperCachedPlayerIdentityResolver identityResolver,
            Executor serverExecutor,
            Executor blockingExecutor) {
        this(
                groupCatalog,
                Objects.requireNonNull(definitionRepository, "definitionRepository")::findActiveByKey,
                startRepository,
                Objects.requireNonNull(identityResolver, "identityResolver")::resolve,
                serverExecutor,
                blockingExecutor,
                Clock.systemUTC(),
                UUID::randomUUID);
    }

    PaperDistributionCampaignCoordinator(
            PaperGroupFileCatalog groupCatalog,
            Function<DefinitionKey, CompletionStage<Optional<LoreDefinition>>> definitionLookup,
            DistributionCampaignStartRepository startRepository,
            Executor blockingExecutor,
            Clock clock,
            Supplier<UUID> campaignIds) {
        this(
                groupCatalog,
                definitionLookup,
                startRepository,
                ignored -> Optional.empty(),
                Runnable::run,
                blockingExecutor,
                clock,
                campaignIds);
    }

    PaperDistributionCampaignCoordinator(
            PaperGroupFileCatalog groupCatalog,
            Function<DefinitionKey, CompletionStage<Optional<LoreDefinition>>> definitionLookup,
            DistributionCampaignStartRepository startRepository,
            Function<String, Optional<UUID>> cachedIdentityLookup,
            Executor serverExecutor,
            Executor blockingExecutor,
            Clock clock,
            Supplier<UUID> campaignIds) {
        this.groupCatalog = Objects.requireNonNull(groupCatalog, "groupCatalog");
        this.definitionLookup = Objects.requireNonNull(definitionLookup, "definitionLookup");
        this.startRepository = Objects.requireNonNull(startRepository, "startRepository");
        this.cachedIdentityLookup = Objects.requireNonNull(cachedIdentityLookup, "cachedIdentityLookup");
        this.serverExecutor = Objects.requireNonNull(serverExecutor, "serverExecutor");
        this.blockingExecutor = Objects.requireNonNull(blockingExecutor, "blockingExecutor");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.campaignIds = Objects.requireNonNull(campaignIds, "campaignIds");
    }

    public CompletionStage<Optional<DistributionCampaignPreview>> preview(
            String sourceName,
            DefinitionKey definitionKey,
            String actorType,
            String actorId) {
        Objects.requireNonNull(sourceName, "sourceName");
        Objects.requireNonNull(definitionKey, "definitionKey");
        return inspectAsync(sourceName).thenCompose(groupFile -> resolveCachedIdentities(groupFile)
                .thenCompose(cachedIdentities -> definitionLookup
                        .apply(definitionKey)
                        .thenApply(definition -> definition.map(selected -> buildPreview(
                                groupFile,
                                selected,
                                cachedIdentities,
                                actorType,
                                actorId)))));
    }

    public CompletionStage<DistributionCampaignConfirmationResult> confirm(
            DistributionCampaignPreview preview) {
        Objects.requireNonNull(preview, "preview");
        return sourceStillMatches(preview).thenCompose(matches -> {
            if (!matches) {
                return CompletableFuture.completedFuture(
                        DistributionCampaignConfirmationResult.sourceChanged(preview.campaignId()));
            }
            return startRepository.start(preview.startRequest()).thenCompose(startResult ->
                    handleDurableStart(preview, startResult));
        });
    }

    private DistributionCampaignPreview buildPreview(
            GroupFileDefinition groupFile,
            LoreDefinition definition,
            Map<Integer, UUID> cachedIdentities,
            String actorType,
            String actorId) {
        if (!definition.active()) {
            throw new IllegalArgumentException("Selected definition is not active");
        }
        long now = clock.millis();
        UUID campaignId = Objects.requireNonNull(campaignIds.get(), "campaignId");
        DistributionCampaign campaign = new DistributionCampaign(
                campaignId,
                groupFile.sourceFingerprint(),
                groupFile.sourceName(),
                groupFile.displayName(),
                definition.id(),
                definition.currentRevision(),
                DistributionCampaignState.DRAFT,
                now,
                now,
                null);
        List<CampaignRecipient> recipients = snapshotRecipients(
                groupFile, cachedIdentities, campaignId, now);
        DistributionCampaignStartRequest request = new DistributionCampaignStartRequest(
                campaign,
                recipients,
                actorType,
                actorId);
        return new DistributionCampaignPreview(groupFile, definition, request);
    }

    private static List<CampaignRecipient> snapshotRecipients(
            GroupFileDefinition groupFile,
            Map<Integer, UUID> cachedIdentities,
            UUID campaignId,
            long now) {
        List<CampaignRecipient> recipients = new ArrayList<>(groupFile.recipients().size());
        for (int index = 0; index < groupFile.recipients().size(); index++) {
            GroupFileRecipient sourceRecipient = groupFile.recipients().get(index);
            UUID playerId = sourceRecipient.explicitPlayerId();
            if (playerId == null) {
                playerId = cachedIdentities.get(index);
            }
            if (playerId == null) {
                recipients.add(CampaignRecipient.unresolvedName(
                        campaignId,
                        index,
                        sourceRecipient.originalValue(),
                        now));
            } else {
                recipients.add(CampaignRecipient.knownPlayer(
                        campaignId,
                        index,
                        playerId,
                        sourceRecipient.originalValue(),
                        now));
            }
        }
        return List.copyOf(recipients);
    }

    private CompletionStage<GroupFileDefinition> inspectAsync(String sourceName) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return groupCatalog.inspect(sourceName);
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        }, blockingExecutor);
    }

    private CompletionStage<Map<Integer, UUID>> resolveCachedIdentities(GroupFileDefinition groupFile) {
        return CompletableFuture.supplyAsync(() -> {
            Map<Integer, UUID> resolved = new ConcurrentHashMap<>();
            for (int index = 0; index < groupFile.recipients().size(); index++) {
                GroupFileRecipient recipient = groupFile.recipients().get(index);
                if (recipient.explicitPlayerId() == null) {
                    int recipientIndex = index;
                    cachedIdentityLookup.apply(recipient.originalValue())
                            .ifPresent(playerId -> resolved.put(recipientIndex, playerId));
                }
            }
            return Map.copyOf(resolved);
        }, serverExecutor);
    }

    private CompletionStage<Boolean> sourceStillMatches(DistributionCampaignPreview preview) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                GroupFileDefinition current = groupCatalog.inspect(preview.groupFile().sourceName());
                return current.sourceFingerprint().equals(preview.groupFile().sourceFingerprint());
            } catch (IOException | IllegalArgumentException exception) {
                return false;
            }
        }, blockingExecutor);
    }

    private CompletionStage<DistributionCampaignConfirmationResult> handleDurableStart(
            DistributionCampaignPreview preview,
            DistributionCampaignStartResult startResult) {
        if (startResult.status() == DistributionCampaignStartResult.Status.SOURCE_ALREADY_USED) {
            return CompletableFuture.completedFuture(
                    DistributionCampaignConfirmationResult.sourceAlreadyUsed(startResult.campaignId()));
        }
        return moveActiveMarker(preview, startResult.campaignId());
    }

    private CompletionStage<DistributionCampaignConfirmationResult> moveActiveMarker(
            DistributionCampaignPreview preview, UUID campaignId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Path marker = groupCatalog.moveToActive(preview.groupFile(), campaignId);
                return DistributionCampaignConfirmationResult.started(campaignId, marker);
            } catch (IOException | IllegalArgumentException exception) {
                return DistributionCampaignConfirmationResult.markerRepairRequired(
                        campaignId,
                        "Campaign is durable, but its active source marker needs reconciliation: "
                                + safeMessage(exception));
            }
        }, blockingExecutor);
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }
}
