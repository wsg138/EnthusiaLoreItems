package net.enthusia.loreitems.paper;

import java.util.Objects;
import java.util.UUID;
import net.enthusia.loreitems.application.LoreItemsAdministrationUseCase;
import net.enthusia.loreitems.application.PageRequest;

/** Validated request construction shared by the tracking administration controller. */
final class PaperTrackingAdministrationRequests {
    private static final int FIRST_PAGE = 1;

    private PaperTrackingAdministrationRequests() {}

    static PageRequest page(int pageNumber, int configuredPageSize, int maximumPageSize) {
        if (pageNumber < FIRST_PAGE) {
            throw new IllegalArgumentException("pageNumber must be positive");
        }
        int pageSize = Math.min(maximumPageSize, configuredPageSize);
        if (pageSize < FIRST_PAGE) {
            throw new IllegalStateException("Configured GUI page size must be positive");
        }
        return new PageRequest(Math.multiplyExact(pageNumber - FIRST_PAGE, pageSize), pageSize);
    }

    static LoreItemsAdministrationUseCase.DuplicateResolutionRequest duplicateResolution(
            UUID playerId, PaperTrackingAdministrationView view) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(view, "view");
        try {
            return new LoreItemsAdministrationUseCase.DuplicateResolutionRequest(
                    view.duplicate.anomalyId(),
                    view.duplicate.stateRevision(),
                    view.selectedObservation.observationId(),
                    "player:" + playerId);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
