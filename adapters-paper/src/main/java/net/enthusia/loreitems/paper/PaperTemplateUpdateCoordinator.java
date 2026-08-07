package net.enthusia.loreitems.paper;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.logging.Level;
import net.enthusia.loreitems.application.DestructiveRemovalExecutionUseCase;
import net.enthusia.loreitems.application.PreparedTemplateUpdate;
import net.enthusia.loreitems.application.TemplateUpdateExecutionUseCase;
import net.enthusia.loreitems.application.TemplateUpdatePrepareResult;
import org.bukkit.plugin.Plugin;

/** Bounded bridge from natural-access observations to destructive-first durable mutation claims. */
final class PaperTemplateUpdateCoordinator implements AutoCloseable {
    private static final int MIN_MAX_IN_FLIGHT = 1;
    private static final int QUEUE_MULTIPLIER = 8;

    private final Plugin plugin;
    private final TemplateUpdateExecutionUseCase useCase;
    private final PaperTemplateUpdateOperator operator;
    private final PaperDestructiveRemovalCoordinator destructiveCoordinator;
    private final Clock clock;
    private final int maxInFlight;
    private final int maxQueued;
    private final Object lock = new Object();
    private final Queue<PaperTemplateUpdateScanner.Candidate> queued = new ArrayDeque<>();
    private final Set<UUID> scheduledInstances = new HashSet<>();

    private int inFlight;
    private boolean saturated;
    private boolean closed;

    PaperTemplateUpdateCoordinator(
            Plugin plugin,
            TemplateUpdateExecutionUseCase useCase,
            PaperTemplateUpdateOperator operator,
            int maxInFlight) {
        this(plugin, useCase, operator, null, null, maxInFlight, Clock.systemUTC());
    }

    PaperTemplateUpdateCoordinator(
            Plugin plugin,
            TemplateUpdateExecutionUseCase useCase,
            PaperTemplateUpdateOperator operator,
            int maxInFlight,
            Clock clock) {
        this(plugin, useCase, operator, null, null, maxInFlight, clock);
    }

    PaperTemplateUpdateCoordinator(
            Plugin plugin,
            TemplateUpdateExecutionUseCase useCase,
            PaperTemplateUpdateOperator operator,
            DestructiveRemovalExecutionUseCase destructiveUseCase,
            PaperDestructiveRemovalOperator destructiveOperator,
            int maxInFlight) {
        this(
                plugin,
                useCase,
                operator,
                destructiveUseCase,
                destructiveOperator,
                maxInFlight,
                Clock.systemUTC());
    }

    PaperTemplateUpdateCoordinator(
            Plugin plugin,
            TemplateUpdateExecutionUseCase useCase,
            PaperTemplateUpdateOperator operator,
            DestructiveRemovalExecutionUseCase destructiveUseCase,
            PaperDestructiveRemovalOperator destructiveOperator,
            int maxInFlight,
            Clock clock) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.useCase = Objects.requireNonNull(useCase, "useCase");
        this.operator = Objects.requireNonNull(operator, "operator");
        this.clock = Objects.requireNonNull(clock, "clock");
        if ((destructiveUseCase == null) != (destructiveOperator == null)) {
            throw new IllegalArgumentException(
                    "Destructive use case and operator must be configured together");
        }
        this.destructiveCoordinator = destructiveUseCase == null
                ? null
                : new PaperDestructiveRemovalCoordinator(
                        plugin, destructiveUseCase, destructiveOperator, clock);
        if (maxInFlight < MIN_MAX_IN_FLIGHT) {
            throw new IllegalArgumentException("maxInFlight must be positive");
        }
        this.maxInFlight = maxInFlight;
        this.maxQueued = Math.multiplyExact(maxInFlight, QUEUE_MULTIPLIER);
    }

    boolean submit(PaperTemplateUpdateScanner.Candidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        boolean dispatch = false;
        synchronized (lock) {
            if (closed) {
                return false;
            }
            UUID instanceId = candidate.identity().instanceId().value();
            if (!scheduledInstances.add(instanceId)) {
                return true;
            }
            if (inFlight < maxInFlight) {
                inFlight++;
                dispatch = true;
            } else if (queued.size() < maxQueued) {
                queued.add(candidate);
                if (queued.size() == maxQueued) {
                    reportSaturation();
                }
            } else {
                scheduledInstances.remove(instanceId);
                reportSaturation();
                return false;
            }
        }
        if (dispatch) {
            start(candidate);
        }
        return true;
    }

    private void start(PaperTemplateUpdateScanner.Candidate candidate) {
        if (destructiveCoordinator == null) {
            startTemplateUpdate(candidate);
            return;
        }
        boolean accepted = destructiveCoordinator.submit(
                candidate,
                () -> startTemplateUpdate(candidate),
                () -> finish(candidate));
        if (!accepted) {
            finish(candidate);
        }
    }

    private void startTemplateUpdate(PaperTemplateUpdateScanner.Candidate candidate) {
        if (isClosed()) {
            finish(candidate);
            return;
        }
        CompletionStage<TemplateUpdatePrepareResult> preparation;
        try {
            preparation = Objects.requireNonNull(
                    useCase.prepare(candidate.identity()),
                    "template-update preparation stage");
        } catch (RuntimeException exception) {
            logFailure("Could not submit template-update preparation.", exception);
            finish(candidate);
            return;
        }
        preparation.whenComplete((result, throwable) -> {
            if (throwable != null) {
                logFailure("Could not prepare a naturally encountered template update.", throwable);
                finish(candidate);
                return;
            }
            handlePreparation(candidate, result);
        });
    }

    private void handlePreparation(
            PaperTemplateUpdateScanner.Candidate candidate,
            TemplateUpdatePrepareResult result) {
        if (result == null) {
            plugin.getLogger().severe(
                    "Template-update preparation completed without a result.");
            finish(candidate);
            return;
        }
        if (result.status() != TemplateUpdatePrepareResult.Status.PREPARED) {
            finish(candidate);
            return;
        }
        PreparedTemplateUpdate update = Objects.requireNonNull(
                result.preparedUpdate(), "prepared template update");
        if (isClosed()) {
            release(candidate, update, "The plugin is stopping.");
            return;
        }
        if (claimExpired(update)) {
            skipExpiredClaim(candidate, update);
            return;
        }
        if (!scheduleMain(() -> applyOnMainThread(candidate, update))) {
            release(
                    candidate,
                    update,
                    "Paper rejected the main-thread template-update task.");
        }
    }

    private void applyOnMainThread(
            PaperTemplateUpdateScanner.Candidate candidate,
            PreparedTemplateUpdate update) {
        if (isClosed()) {
            release(candidate, update, "The plugin is stopping.");
            return;
        }
        if (claimExpired(update)) {
            skipExpiredClaim(candidate, update);
            return;
        }
        PaperTemplateUpdateOperator.ApplyResult result;
        try {
            result = operator.apply(plugin, candidate.reference(), update);
        } catch (RuntimeException exception) {
            requireReview(
                    candidate,
                    update,
                    "Paper failed during the physical template update: "
                            + exception.getClass().getSimpleName(),
                    null,
                    null,
                    exception);
            return;
        }
        switch (result.status()) {
            case APPLIED, ALREADY_APPLIED -> complete(candidate, update, result);
            case NOT_ACCESSIBLE -> release(candidate, update, result.detail());
            case REVIEW_REQUIRED -> requireReview(
                    candidate,
                    update,
                    result.detail(),
                    result.beforeFingerprint(),
                    result.afterFingerprint(),
                    null);
            default -> requireReview(
                    candidate,
                    update,
                    "Paper returned an unsupported physical template-update result.",
                    result.beforeFingerprint(),
                    result.afterFingerprint(),
                    null);
        }
    }

    private void complete(
            PaperTemplateUpdateScanner.Candidate candidate,
            PreparedTemplateUpdate update,
            PaperTemplateUpdateOperator.ApplyResult result) {
        CompletionStage<Boolean> completion;
        try {
            completion = Objects.requireNonNull(
                    useCase.complete(
                            update,
                            Objects.requireNonNull(
                                    result.beforeFingerprint(), "beforeFingerprint"),
                            Objects.requireNonNull(
                                    result.afterFingerprint(), "afterFingerprint")),
                    "template-update completion stage");
        } catch (RuntimeException exception) {
            requireReview(
                    candidate,
                    update,
                    "The physical template changed but durable completion could not be submitted.",
                    result.beforeFingerprint(),
                    result.afterFingerprint(),
                    exception);
            return;
        }
        completion.whenComplete((completed, throwable) -> {
            if (throwable != null) {
                requireReview(
                        candidate,
                        update,
                        "The physical template changed but durable completion failed.",
                        result.beforeFingerprint(),
                        result.afterFingerprint(),
                        throwable);
            } else if (!Boolean.TRUE.equals(completed)) {
                requireReview(
                        candidate,
                        update,
                        "The physical template changed but the durable claim fence was lost.",
                        result.beforeFingerprint(),
                        result.afterFingerprint(),
                        null);
            } else {
                finish(candidate);
            }
        });
    }

    private void release(
            PaperTemplateUpdateScanner.Candidate candidate,
            PreparedTemplateUpdate update,
            String reason) {
        CompletionStage<Boolean> release;
        try {
            release = Objects.requireNonNull(
                    useCase.release(update, reason),
                    "template-update release stage");
        } catch (RuntimeException exception) {
            logFailure("Could not submit a safe template-update claim release.", exception);
            finish(candidate);
            return;
        }
        release.whenComplete((released, throwable) -> {
            if (throwable != null) {
                logFailure("Could not persist a safe template-update claim release.", throwable);
            } else if (!Boolean.TRUE.equals(released)) {
                plugin.getLogger().warning(
                        "Template-update claim could not be released before its lease expiry: "
                                + update.mutationId());
            }
            finish(candidate);
        });
    }

    private void requireReview(
            PaperTemplateUpdateScanner.Candidate candidate,
            PreparedTemplateUpdate update,
            String reason,
            String beforeFingerprint,
            String afterFingerprint,
            Throwable precedingFailure) {
        if (precedingFailure != null) {
            logFailure(
                    "Template update entered review after an operational failure.",
                    precedingFailure);
        }
        CompletionStage<Boolean> review;
        try {
            review = Objects.requireNonNull(
                    useCase.requireReview(
                            update,
                            reason,
                            beforeFingerprint,
                            afterFingerprint),
                    "template-update review stage");
        } catch (RuntimeException exception) {
            logFailure("Could not submit template-update review persistence.", exception);
            finish(candidate);
            return;
        }
        review.whenComplete((reviewed, throwable) -> {
            if (throwable != null) {
                logFailure("Could not persist template-update review state.", throwable);
            } else if (!Boolean.TRUE.equals(reviewed)) {
                plugin.getLogger().severe(
                        "Template update could not complete or enter review: "
                                + update.mutationId());
            }
            finish(candidate);
        });
    }

    private void skipExpiredClaim(
            PaperTemplateUpdateScanner.Candidate candidate,
            PreparedTemplateUpdate update) {
        plugin.getLogger().warning(
                "Skipped physical template update for expired claim "
                        + update.mutationId()
                        + "; bounded recovery will move it to REVIEW_REQUIRED.");
        finish(candidate);
    }

    private boolean claimExpired(PreparedTemplateUpdate update) {
        return clock.millis() >= update.claimExpiresAtEpochMillis();
    }

    private void finish(PaperTemplateUpdateScanner.Candidate completed) {
        PaperTemplateUpdateScanner.Candidate next = null;
        synchronized (lock) {
            scheduledInstances.remove(completed.identity().instanceId().value());
            if (inFlight <= 0) {
                throw new IllegalStateException("Template-update in-flight accounting underflow");
            }
            inFlight--;
            if (!closed && !queued.isEmpty() && inFlight < maxInFlight) {
                next = queued.remove();
                inFlight++;
            }
            if (queued.isEmpty() && saturated) {
                saturated = false;
                plugin.getLogger().fine("Template-update backlog has drained.");
            }
        }
        if (next != null) {
            start(next);
        }
    }

    private void reportSaturation() {
        if (!saturated) {
            saturated = true;
            plugin.getLogger().warning(
                    "Template-update backlog is full; pending durable mutations were preserved.");
        }
    }

    private boolean scheduleMain(Runnable task) {
        try {
            plugin.getServer().getScheduler().runTask(plugin, task);
            return true;
        } catch (RuntimeException exception) {
            plugin.getLogger().log(
                    Level.FINE,
                    "Could not schedule template-update main-thread work during shutdown.",
                    exception);
            return false;
        }
    }

    private boolean isClosed() {
        synchronized (lock) {
            return closed;
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
        synchronized (lock) {
            closed = true;
            for (PaperTemplateUpdateScanner.Candidate candidate : queued) {
                scheduledInstances.remove(candidate.identity().instanceId().value());
            }
            queued.clear();
            saturated = false;
        }
        if (destructiveCoordinator != null) {
            destructiveCoordinator.close();
        }
    }
}
