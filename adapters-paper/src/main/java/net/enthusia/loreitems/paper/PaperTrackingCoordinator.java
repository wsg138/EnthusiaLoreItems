package net.enthusia.loreitems.paper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import org.bukkit.plugin.Plugin;

/** Bounded bridge between Paper-thread observations and asynchronous durable persistence. */
public final class PaperTrackingCoordinator implements AutoCloseable {
    private static final int MIN_CAPACITY = 1;
    private static final int QUEUE_MULTIPLIER = 8;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5L;

    private final Plugin plugin;
    private final Supplier<TrackingObservationUseCase> useCaseSupplier;
    private final IntSupplier maxInFlightSupplier;
    private final MetricsPort metrics;
    private final Object lock = new Object();
    private final Queue<PendingObservation> queued = new ArrayDeque<>();
    private final CompletableFuture<Void> quiesced = new CompletableFuture<>();

    private int inFlight;
    private boolean saturated;
    private boolean invalidBudgetReported;
    private boolean closed;

    public PaperTrackingCoordinator(
            Plugin plugin,
            Supplier<TrackingObservationUseCase> useCaseSupplier,
            IntSupplier maxInFlightSupplier,
            MetricsPort metrics) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.useCaseSupplier = Objects.requireNonNull(useCaseSupplier, "useCaseSupplier");
        this.maxInFlightSupplier = Objects.requireNonNull(
                maxInFlightSupplier, "maxInFlightSupplier");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        currentMaxInFlight();
    }

    public boolean submit(TrackingObservationUseCase.Request request) {
        Objects.requireNonNull(request, "request");
        PendingObservation observation;
        try {
            observation = new PendingObservation(
                    Objects.requireNonNull(
                            useCaseSupplier.get(), "tracking use case supplier returned null"),
                    request);
        } catch (RuntimeException exception) {
            metrics.increment("tracking.failed");
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Could not resolve lore-item tracking persistence.",
                    exception);
            return false;
        }

        boolean dispatch;
        synchronized (lock) {
            if (closed) {
                metrics.increment("tracking.rejected");
                return false;
            }
            int maxInFlight = currentMaxInFlight();
            int maxQueued = Math.multiplyExact(maxInFlight, QUEUE_MULTIPLIER);
            if (inFlight < maxInFlight) {
                inFlight++;
                dispatch = true;
            } else if (queued.size() < maxQueued) {
                queued.add(observation);
                dispatch = false;
                if (queued.size() == maxQueued) {
                    reportSaturation();
                }
            } else {
                metrics.increment("tracking.rejected");
                updateGauges();
                reportSaturation();
                return false;
            }
            metrics.increment("tracking.accepted");
            updateGauges();
        }
        if (dispatch) {
            startCounted(observation);
        }
        return true;
    }

    private void reportSaturation() {
        if (!saturated) {
            saturated = true;
            plugin.getLogger().warning(
                    "Lore-item tracking backlog is full; previous durable evidence was preserved.");
        }
    }

    /** Starts one request whose in-flight slot has already been counted exactly once. */
    private void startCounted(PendingObservation observation) {
        long startedAt = System.nanoTime();
        CompletionStage<TrackingObservationUseCase.Result> stage;
        try {
            stage = Objects.requireNonNull(
                    observation.useCase().record(observation.request()),
                    "tracking use case returned null");
        } catch (RuntimeException exception) {
            metrics.increment("tracking.failed");
            plugin.getLogger().log(Level.SEVERE, "Could not start lore-item tracking.", exception);
            completeCountedRequest();
            return;
        }
        stage.whenComplete((result, failure) -> {
            metrics.recordDurationNanos(
                    "tracking.persistence_nanos", System.nanoTime() - startedAt);
            if (failure != null) {
                metrics.increment("tracking.failed");
                plugin.getLogger().log(
                        Level.SEVERE,
                        "Could not persist lore-item tracking evidence.",
                        unwrap(failure));
            } else {
                metrics.increment("tracking.completed");
                if (result != null
                        && result.status()
                                == TrackingObservationUseCase.Status.CONFLICT_RECORDED) {
                    metrics.increment("tracking.conflicts");
                }
            }
            completeCountedRequest();
        });
    }

    private void completeCountedRequest() {
        Optional<PendingObservation> next = Optional.empty();
        synchronized (lock) {
            if (inFlight <= 0) {
                throw new IllegalStateException("Tracking in-flight accounting underflow");
            }
            inFlight--;
            if (!queued.isEmpty() && inFlight < completionMaxInFlight()) {
                next = Optional.of(queued.remove());
                inFlight++;
            }
            if (queued.isEmpty() && saturated) {
                saturated = false;
                plugin.getLogger().fine("Lore-item tracking backlog has drained.");
            }
            completeQuiescenceIfDrained();
            updateGauges();
        }
        next.ifPresent(this::startCounted);
    }

    private void completeQuiescenceIfDrained() {
        if (closed && queued.isEmpty() && inFlight == 0) {
            quiesced.complete(null);
        }
    }

    private int completionMaxInFlight() {
        try {
            int value = maxInFlightSupplier.getAsInt();
            if (value >= MIN_CAPACITY) {
                invalidBudgetReported = false;
                return value;
            }
        } catch (RuntimeException exception) {
            reportInvalidBudget(exception);
            return MIN_CAPACITY;
        }
        reportInvalidBudget(null);
        return MIN_CAPACITY;
    }

    private void reportInvalidBudget(RuntimeException exception) {
        if (invalidBudgetReported) {
            return;
        }
        invalidBudgetReported = true;
        if (exception == null) {
            plugin.getLogger().warning(
                    "Invalid tracking budget; completion processing is using minimum capacity.");
        } else {
            plugin.getLogger().log(
                    Level.WARNING,
                    "Could not read the tracking budget; completion processing is using minimum capacity.",
                    exception);
        }
    }

    private int currentMaxInFlight() {
        int value = maxInFlightSupplier.getAsInt();
        if (value < MIN_CAPACITY) {
            throw new IllegalStateException("Configured tracking budget must be positive");
        }
        return value;
    }

    private void updateGauges() {
        metrics.setGauge("tracking.queued", queued.size());
        metrics.setGauge("tracking.in_flight", inFlight);
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException exception && exception.getCause() != null) {
            return exception.getCause();
        }
        return throwable;
    }

    @Override
    public void close() {
        List<PendingObservation> starting = new ArrayList<>();
        boolean firstClose;
        synchronized (lock) {
            firstClose = !closed;
            if (firstClose) {
                closed = true;
                saturated = false;
                int maxInFlight = completionMaxInFlight();
                while (inFlight < maxInFlight && !queued.isEmpty()) {
                    starting.add(queued.remove());
                    inFlight++;
                }
                completeQuiescenceIfDrained();
                updateGauges();
            }
        }
        if (firstClose) {
            starting.forEach(this::startCounted);
        }
        awaitQuiescence();
    }

    private void awaitQuiescence() {
        try {
            quiesced.get(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            plugin.getLogger().log(
                    Level.WARNING,
                    "Interrupted while draining lore-item tracking evidence.",
                    exception);
        } catch (TimeoutException exception) {
            plugin.getLogger().warning(
                    "Timed out while draining lore-item tracking evidence; SQLite shutdown will "
                            + "still attempt its bounded executor drain.");
        } catch (java.util.concurrent.ExecutionException exception) {
            plugin.getLogger().log(
                    Level.WARNING,
                    "Lore-item tracking quiescence failed unexpectedly.",
                    exception.getCause());
        }
    }

    private record PendingObservation(
            TrackingObservationUseCase useCase,
            TrackingObservationUseCase.Request request) {
        private PendingObservation {
            Objects.requireNonNull(useCase, "useCase");
            Objects.requireNonNull(request, "request");
        }
    }
}
