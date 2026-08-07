package net.enthusia.loreitems.paper;

import java.util.Objects;
import java.util.concurrent.Executor;
import net.enthusia.loreitems.application.DistributionCampaignAdministrationUseCase;

/** Immutable dependency bundle for the distribution operator command surface. */
public record DistributionCampaignCommandDependencies(
        PaperGroupFileCatalog groupCatalog,
        PaperDistributionCampaignCoordinator coordinator,
        DistributionCampaignAdministrationUseCase administration,
        PaperDistributionMarkerReconciler markerReconciler,
        DistributionCancellationFence cancellationFence,
        Runnable markerWake,
        Executor blockingExecutor) {
    public DistributionCampaignCommandDependencies {
        Objects.requireNonNull(groupCatalog, "groupCatalog");
        Objects.requireNonNull(coordinator, "coordinator");
        Objects.requireNonNull(administration, "administration");
        Objects.requireNonNull(markerReconciler, "markerReconciler");
        Objects.requireNonNull(cancellationFence, "cancellationFence");
        Objects.requireNonNull(markerWake, "markerWake");
        Objects.requireNonNull(blockingExecutor, "blockingExecutor");
    }
}
