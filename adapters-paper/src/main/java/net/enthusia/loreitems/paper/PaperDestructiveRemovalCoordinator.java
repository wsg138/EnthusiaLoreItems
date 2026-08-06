package net.enthusia.loreitems.paper;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.logging.Level;
import net.enthusia.loreitems.application.DestructiveRemovalExecutionUseCase;
import net.enthusia.loreitems.domain.DestructiveEffectState;
import org.bukkit.plugin.Plugin;

/**
 * Destructive-first phase for one naturally encountered item. The parent coordinator owns
 * deduplication and capacity; this phase owns durable claims and main-thread physical removal.
 */
final class PaperDestructiveRemovalCoordinator implements AutoCloseable {
    private final Plugin plugin;
    private final DestructiveRemovalExecutionUseCase useCase;
    private final PaperDestructiveRemovalOperator operator;
    private final Clock clock;
    private volatile boolean closed;

    PaperDestructiveRemovalCoordinator(
            Plugin plugin,
            DestructiveRemovalExecutionUseCase useCase,
            PaperDestructiveRemovalOperator operator,
            Clock clock) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.useCase = Objects.requireNonNull(useCase, "useCase");
        this.operator = Objects.requireNonNull(operator, "operator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    boolean submit(
            PaperTemplateUpdateScanner.Candidate candidate,
            Runnable noPendingWork,
            Runnable finished) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(noPendingWork, "noPendingWork");
        Objects.requireNonNull(finished, "finished");
        if (closed) {
            return false;
        }
        return scheduleMain(() -> observeOnMainThread(candidate, noPendingWork, finished));
    }

    private void observeOnMainThread(
            PaperTemplateUpdateScanner.Candidate candidate,
            Runnable noPendingWork,
            Runnable finished) {
        if (closed) {
            finished.run();
            return;
        }
        PaperDestructiveRemovalOperator.ObservationResult observation;
        try {
            observation = operator.observe(plugin, candidate);
        } catch (RuntimeException exception) {
            logFailure("Could not observe a natural destructive-removal candidate.", exception);
            finished.run();
            return;
        }
        if (observation.status()
                != PaperDestructiveRemovalOperator.ObservationResult.Status.OBSERVED) {
            if (observation.status()
                    == PaperDestructiveRemovalOperator.ObservationResult.Status.REVIEW_REQUIRED) {
                plugin.getLogger().warning(observation.detail());
            }
            finished.run();
            return;
        }
        prepare(
                candidate,
                Objects.requireNonNull(observation.observation(), "observation"),
                noPendingWork,
                finished);
    }

    private void prepare(
            PaperTemplateUpdateScanner.Candidate candidate,
            DestructiveRemovalExecutionUseCase.Observation observation,
            Runnable noPendingWork,
            Runnable finished) {
        CompletionStage<DestructiveRemovalExecutionUseCase.PrepareResult> stage;
        try {
            stage = Objects.requireNonNull(
                    useCase.prepare(observation),
                    "destructive-removal preparation stage");
        } catch (RuntimeException exception) {
            logFailure("Could not submit destructive-removal preparation.", exception);
            finished.run();
            return;
        }
        stage.whenComplete((result, throwable) -> {
            if (throwable != null) {
                logFailure(
                        "Could not prepare a naturally encountered destructive removal.",
                        throwable);
                finished.run();
                return;
            }
            handlePreparation(candidate, result, noPendingWork, finished);
        });
    }

    private void handlePreparation(
            PaperTemplateUpdateScanner.Candidate candidate,
            DestructiveRemovalExecutionUseCase.PrepareResult result,
            Runnable noPendingWork,
            Runnable finished) {
        if (result == null) {
            plugin.getLogger().severe(
                    "Destructive-removal preparation completed without a result.");
            finished.run();
            return;
        }
        switch (result.status()) {
            case NO_PENDING_WORK -> noPendingWork.run();
            case REVIEW_REQUIRED -> {
                plugin.getLogger().warning(result.detail());
                finished.run();
            }
            case PREPARED -> handlePrepared(
                    candidate,
                    Objects.requireNonNull(result.preparedRemoval(), "prepared removal"),
                    finished);
            default -> {
                plugin.getLogger().severe(
                        "Destructive-removal preparation returned an unsupported result.");
                finished.run();
            }
        }
    }

    private void handlePrepared(
            PaperTemplateUpdateScanner.Candidate candidate,
            DestructiveRemovalExecutionUseCase.PreparedRemoval removal,
            Runnable finished) {
        if (closed) {
            release(removal, "The plugin is stopping.", finished);
            return;
        }
        if (claimExpired(removal)) {
            skipExpiredClaim(removal, finished);
            return;
        }
        if (!scheduleMain(() -> removeOnMainThread(candidate, removal, finished))) {
            release(
                    removal,
                    "Paper rejected the main-thread destructive-removal task.",
                    finished);
        }
    }

    private void removeOnMainThread(
            PaperTemplateUpdateScanner.Candidate candidate,
            DestructiveRemovalExecutionUseCase.PreparedRemoval removal,
            Runnable finished) {
        if (closed) {
            release(removal, "The plugin is stopping.", finished);
            return;
        }
        if (claimExpired(removal)) {
            skipExpiredClaim(removal, finished);
            return;
        }
        PaperDestructiveRemovalOperator.ApplyResult result;
        try {
            result = operator.remove(plugin, candidate.reference(), removal);
        } catch (RuntimeException exception) {
            requireReview(
                    removal,
                    DestructiveEffectState.UNKNOWN,
                    null,
                    null,
                    "Paper failed during the physical destructive removal: "
                            + exception.getClass().getSimpleName(),
                    exception,
                    finished);
            return;
        }
        switch (result.status()) {
            case REMOVED -> complete(removal, result, finished);
            case NOT_ACCESSIBLE -> release(removal, result.detail(), finished);
            case REVIEW_REQUIRED -> requireReview(
                    removal,
                    result.effectState(),
                    result.beforeFingerprint(),
                    result.afterFingerprint(),
                    result.detail(),
                    null,
                    finished);
            default -> requireReview(
                    removal,
                    DestructiveEffectState.UNKNOWN,
                    result.beforeFingerprint(),
                    result.afterFingerprint(),
                    "Paper returned an unsupported physical destructive-removal result.",
                    null,
                    finished);
        }
    }

    private void complete(
            DestructiveRemovalExecutionUseCase.PreparedRemoval removal,
            PaperDestructiveRemovalOperator.ApplyResult result,
            Runnable finished) {
        CompletionStage<Boolean> completion;
        try {
            completion = Objects.requireNonNull(
                    useCase.complete(
                            removal,
                            Objects.requireNonNull(
                                    result.beforeFingerprint(), "beforeFingerprint")),
                    "destructive-removal completion stage");
        } catch (RuntimeException exception) {
            requireReview(
                    removal,
                    DestructiveEffectState.REMOVED_OBSERVED,
                    result.beforeFingerprint(),
                    result.afterFingerprint(),
                    "The item was removed but durable completion could not be submitted.",
                    exception,
                    finished);
            return;
        }
        completion.whenComplete((completed, throwable) -> {
            if (throwable != null) {
                requireReview(
                        removal,
                        DestructiveEffectState.REMOVED_OBSERVED,
                        result.beforeFingerprint(),
                        result.afterFingerprint(),
                        "The item was removed but durable completion failed.",
                        throwable,
                        finished);
            } else if (!Boolean.TRUE.equals(completed)) {
                requireReview(
                        removal,
                        DestructiveEffectState.REMOVED_OBSERVED,
                        result.beforeFingerprint(),
                        result.afterFingerprint(),
                        "The item was removed but the durable destructive claim fence was lost.",
                        null,
                        finished);
            } else {
                finished.run();
            }
        });
    }

    private void release(
            DestructiveRemovalExecutionUseCase.PreparedRemoval removal,
            String reason,
            Runnable finished) {
        CompletionStage<Boolean> release;
        try {
            release = Objects.requireNonNull(
                    useCase.release(removal, reason),
                    "destructive-removal release stage");
        } catch (RuntimeException exception) {
            logFailure("Could not submit a safe destructive-removal claim release.", exception);
            finished.run();
            return;
        }
        release.whenComplete((released, throwable) -> {
            if (throwable != null) {
                logFailure(
                        "Could not persist a safe destructive-removal claim release.",
                        throwable);
            } else if (!Boolean.TRUE.equals(released)) {
                plugin.getLogger().warning(
                        "Destructive-removal claim could not be released before lease expiry: "
                                + removal.operationId());
            }
            finished.run();
        });
    }

    private void requireReview(
            DestructiveRemovalExecutionUseCase.PreparedRemoval removal,
            DestructiveEffectState effectState,
            String beforeFingerprint,
            String afterFingerprint,
            String detail,
            Throwable precedingFailure,
            Runnable finished) {
        if (precedingFailure != null) {
            logFailure(
                    "Destructive removal entered review after an operational failure.",
                    precedingFailure);
        }
        CompletionStage<Boolean> review;
        try {
            review = Objects.requireNonNull(
                    useCase.requireReview(
                            removal,
                            effectState,
                            beforeFingerprint,
                            afterFingerprint,
                            detail),
                    "destructive-removal review stage");
        } catch (RuntimeException exception) {
            logFailure("Could not submit destructive-removal review persistence.", exception);
            finished.run();
            return;
        }
        review.whenComplete((reviewed, throwable) -> {
            if (throwable != null) {
                logFailure("Could not persist destructive-removal review state.", throwable);
            } else if (!Boolean.TRUE.equals(reviewed)) {
                plugin.getLogger().severe(
                        "Destructive removal could not complete or enter review: "
                                + removal.operationId());
            }
            finished.run();
        });
    }

    private void skipExpiredClaim(
            DestructiveRemovalExecutionUseCase.PreparedRemoval removal,
            Runnable finished) {
        plugin.getLogger().warning(
                "Skipped physical destructive removal for expired claim on operation "
                        + removal.operationId()
                        + "; bounded recovery will move it to REVIEW_REQUIRED.");
        finished.run();
    }

    private boolean claimExpired(DestructiveRemovalExecutionUseCase.PreparedRemoval removal) {
        return clock.millis() >= removal.claimExpiresAtEpochMillis();
    }

    private boolean scheduleMain(Runnable task) {
        try {
            plugin.getServer().getScheduler().runTask(plugin, task);
            return true;
        } catch (RuntimeException exception) {
            plugin.getLogger().log(
                    Level.FINE,
                    "Could not schedule destructive-removal main-thread work during shutdown.",
                    exception);
            return false;
        }
    }

    private void logFailure(String message, Throwable throwable) {
        plugin.getLogger().log(Level.SEVERE, message, unwrap(throwable));
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException exception && exception.getCause() != null) {
            return exception.getCause();
        }
        return throwable;
    }

    @Override
    public void close() {
        closed = true;
    }
}
