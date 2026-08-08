package net.enthusia.loreitems.paper;

import java.util.Objects;
import net.enthusia.loreitems.application.DestructiveRemovalExecutionUseCase;
import net.enthusia.loreitems.application.ItemIdentityReadResult;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.domain.DestructiveEffectState;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Main-thread observation and verified physical removal at one reload-safe reference. */
final class PaperDestructiveRemovalOperator {
    private final PaperItemIdentityCodec identityCodec;

    PaperDestructiveRemovalOperator() {
        this(new PaperItemIdentityCodec());
    }

    PaperDestructiveRemovalOperator(PaperItemIdentityCodec identityCodec) {
        this.identityCodec = Objects.requireNonNull(identityCodec, "identityCodec");
    }

    ObservationResult observe(
            Plugin plugin,
            PaperTemplateUpdateScanner.Candidate candidate) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(candidate, "candidate");
        PaperTemplateUpdateReference.Resolved resolved =
                candidate.reference().resolve(plugin).orElse(null);
        if (resolved == null) {
            return ObservationResult.notAccessible();
        }
        ItemStack current = resolved.originalItem();
        String fingerprint;
        try {
            fingerprint = PaperItemFingerprint.of(current);
        } catch (RuntimeException exception) {
            return ObservationResult.reviewRequired(
                    "Paper could not fingerprint the naturally encountered item: "
                            + exception.getClass().getSimpleName());
        }
        LoreItemIdentity currentIdentity;
        try {
            currentIdentity = trackedIdentity(current);
        } catch (RuntimeException exception) {
            return ObservationResult.reviewRequired(
                    "Paper could not read the naturally encountered identity: "
                            + exception.getClass().getSimpleName());
        }
        if (currentIdentity == null || !currentIdentity.equals(candidate.identity())) {
            return ObservationResult.reviewRequired(
                    "The naturally encountered item changed before destructive preparation.");
        }
        PaperTemplateUpdateReference.DestructiveLocation location =
                PaperDestructiveLocationResolver.resolve(plugin, candidate.reference()).orElse(null);
        if (location == null) {
            return ObservationResult.notAccessible();
        }
        return ObservationResult.observed(new DestructiveRemovalExecutionUseCase.Observation(
                currentIdentity,
                location.locationType(),
                location.locationKey(),
                location.containerPath(),
                fingerprint));
    }

    ApplyResult remove(
            Plugin plugin,
            PaperTemplateUpdateReference reference,
            DestructiveRemovalExecutionUseCase.PreparedRemoval removal) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(removal, "removal");
        PaperTemplateUpdateReference.Resolved resolved =
                reference.resolve(plugin).orElse(null);
        if (resolved == null) {
            return ApplyResult.notAccessible();
        }
        BeforeRemoval before = inspectBefore(resolved, removal);
        if (before.failure() != null) {
            return before.failure();
        }
        ApplyResult mutationFailure = applyPhysicalRemoval(resolved, before.fingerprint());
        if (mutationFailure != null) {
            return mutationFailure;
        }
        return verifyRemoval(resolved, removal, before.fingerprint());
    }

    private BeforeRemoval inspectBefore(
            PaperTemplateUpdateReference.Resolved resolved,
            DestructiveRemovalExecutionUseCase.PreparedRemoval removal) {
        ItemStack current = resolved.originalItem();
        String fingerprint;
        try {
            fingerprint = PaperItemFingerprint.of(current);
        } catch (RuntimeException exception) {
            return BeforeRemoval.failed(ApplyResult.reviewRequired(
                    DestructiveEffectState.NONE_OBSERVED,
                    null,
                    null,
                    "Paper could not fingerprint the claimed item before removal: "
                            + exception.getClass().getSimpleName()));
        }
        LoreItemIdentity identity;
        try {
            identity = trackedIdentity(current);
        } catch (RuntimeException exception) {
            return BeforeRemoval.failed(ApplyResult.reviewRequired(
                    DestructiveEffectState.NONE_OBSERVED,
                    fingerprint,
                    null,
                    "Paper could not read the claimed item identity before removal: "
                            + exception.getClass().getSimpleName()));
        }
        if (identity == null
                || !identity.equals(removal.observedIdentity())
                || !fingerprint.equals(removal.beforeFingerprint())) {
            return BeforeRemoval.failed(ApplyResult.reviewRequired(
                    DestructiveEffectState.NONE_OBSERVED,
                    fingerprint,
                    null,
                    "The claimed physical item changed after durable destructive preparation."));
        }
        return BeforeRemoval.verified(fingerprint);
    }

    private static ApplyResult applyPhysicalRemoval(
            PaperTemplateUpdateReference.Resolved resolved,
            String beforeFingerprint) {
        final boolean removed;
        try {
            removed = resolved.remove();
        } catch (RuntimeException exception) {
            return ApplyResult.reviewRequired(
                    DestructiveEffectState.UNKNOWN,
                    beforeFingerprint,
                    null,
                    "Paper failed while applying the physical removal: "
                            + exception.getClass().getSimpleName());
        }
        if (removed) {
            return null;
        }
        return ApplyResult.reviewRequired(
                DestructiveEffectState.NONE_OBSERVED,
                beforeFingerprint,
                null,
                "The physical reference changed before the claimed item could be removed.");
    }

    private ApplyResult verifyRemoval(
            PaperTemplateUpdateReference.Resolved resolved,
            DestructiveRemovalExecutionUseCase.PreparedRemoval removal,
            String beforeFingerprint) {
        ItemStack stored;
        try {
            stored = resolved.readStored();
        } catch (RuntimeException exception) {
            return ApplyResult.reviewRequired(
                    DestructiveEffectState.UNKNOWN,
                    beforeFingerprint,
                    null,
                    "The item was removed but Paper could not verify the physical reference: "
                            + exception.getClass().getSimpleName());
        }
        String afterFingerprint = fingerprintOrNull(stored);
        if (stored == null || stored.getType().isAir()) {
            return ApplyResult.removed(beforeFingerprint, null);
        }
        LoreItemIdentity remainingIdentity;
        try {
            remainingIdentity = trackedIdentity(stored);
        } catch (RuntimeException exception) {
            return ApplyResult.reviewRequired(
                    DestructiveEffectState.AMBIGUOUS,
                    beforeFingerprint,
                    afterFingerprint,
                    "The item was removed but the resulting physical reference could not be read.");
        }
        if (remainingIdentity == null
                || !remainingIdentity.instanceId().equals(removal.instanceId())) {
            return ApplyResult.removed(beforeFingerprint, afterFingerprint);
        }
        return ApplyResult.reviewRequired(
                DestructiveEffectState.AMBIGUOUS,
                beforeFingerprint,
                afterFingerprint,
                "The target instance still appears at the physical reference after removal.");
    }

    private LoreItemIdentity trackedIdentity(ItemStack item) {
        ItemIdentityReadResult result = identityCodec.readIdentity(item);
        return result instanceof ItemIdentityReadResult.Tracked tracked
                ? tracked.identity()
                : null;
    }

    private static String fingerprintOrNull(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        try {
            return PaperItemFingerprint.of(item);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private record BeforeRemoval(String fingerprint, ApplyResult failure) {
        private BeforeRemoval {
            if ((fingerprint == null) == (failure == null)) {
                throw new IllegalArgumentException(
                        "Before-removal evidence must be either verified or failed");
            }
        }

        private static BeforeRemoval verified(String fingerprint) {
            return new BeforeRemoval(
                    Objects.requireNonNull(fingerprint, "fingerprint"), null);
        }

        private static BeforeRemoval failed(ApplyResult failure) {
            return new BeforeRemoval(null, Objects.requireNonNull(failure, "failure"));
        }
    }

    record ObservationResult(
            Status status,
            DestructiveRemovalExecutionUseCase.Observation observation,
            String detail) {
        ObservationResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(detail, "detail");
            if ((status == Status.OBSERVED) != (observation != null)) {
                throw new IllegalArgumentException("Only OBSERVED results contain evidence");
            }
        }

        static ObservationResult observed(
                DestructiveRemovalExecutionUseCase.Observation observation) {
            return new ObservationResult(
                    Status.OBSERVED,
                    Objects.requireNonNull(observation, "observation"),
                    "The physical item was observed for destructive-first preparation.");
        }

        static ObservationResult notAccessible() {
            return new ObservationResult(
                    Status.NOT_ACCESSIBLE,
                    null,
                    "The item moved or unloaded before destructive preparation.");
        }

        static ObservationResult reviewRequired(String detail) {
            return new ObservationResult(Status.REVIEW_REQUIRED, null, detail);
        }

        enum Status {
            OBSERVED,
            NOT_ACCESSIBLE,
            REVIEW_REQUIRED
        }
    }

    record ApplyResult(
            Status status,
            DestructiveEffectState effectState,
            String beforeFingerprint,
            String afterFingerprint,
            String detail) {
        ApplyResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(effectState, "effectState");
            Objects.requireNonNull(detail, "detail");
            if (detail.isBlank()) {
                throw new IllegalArgumentException("detail must not be blank");
            }
            if (status == Status.REMOVED && beforeFingerprint == null) {
                throw new IllegalArgumentException(
                        "Verified removal requires a before fingerprint");
            }
        }

        static ApplyResult removed(String beforeFingerprint, String afterFingerprint) {
            return new ApplyResult(
                    Status.REMOVED,
                    DestructiveEffectState.REMOVED_OBSERVED,
                    Objects.requireNonNull(beforeFingerprint, "beforeFingerprint"),
                    afterFingerprint,
                    "The claimed item was physically removed and verified at the same reference.");
        }

        static ApplyResult notAccessible() {
            return new ApplyResult(
                    Status.NOT_ACCESSIBLE,
                    DestructiveEffectState.NONE_OBSERVED,
                    null,
                    null,
                    "The item moved or unloaded before the claimed removal ran.");
        }

        static ApplyResult reviewRequired(
                DestructiveEffectState effectState,
                String beforeFingerprint,
                String afterFingerprint,
                String detail) {
            return new ApplyResult(
                    Status.REVIEW_REQUIRED,
                    effectState,
                    beforeFingerprint,
                    afterFingerprint,
                    detail);
        }

        enum Status {
            REMOVED,
            NOT_ACCESSIBLE,
            REVIEW_REQUIRED
        }
    }
}
