package net.enthusia.loreitems.paper;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Dispatches unique inventory candidates while preserving natural-access retry on saturation. */
final class PaperTemplateUpdateCandidateDispatcher {
    private PaperTemplateUpdateCandidateDispatcher() {
    }

    static void dispatch(
            List<PaperTemplateUpdateScanner.Candidate> candidates,
            Predicate<PaperTemplateUpdateScanner.Candidate> submitter,
            Consumer<PaperInventoryReference> retrySink) {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(submitter, "submitter");
        Objects.requireNonNull(retrySink, "retrySink");
        for (PaperTemplateUpdateScanner.Candidate candidate : candidates) {
            if (!submitter.test(candidate)) {
                PaperTemplateUpdateReference reference = candidate.reference();
                if (!(reference instanceof PaperTemplateUpdateItemReference itemReference)) {
                    throw new IllegalArgumentException(
                            "Inventory candidate dispatcher received a non-inventory reference");
                }
                retrySink.accept(itemReference.inventoryReference());
            }
        }
    }
}
