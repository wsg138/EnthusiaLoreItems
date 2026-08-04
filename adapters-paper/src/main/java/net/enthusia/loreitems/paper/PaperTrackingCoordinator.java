package net.enthusia.loreitems.paper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
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

    private final Plugin plugin;
    private final Supplier<TrackingObservationUseCase> useCaseSupplier;
    private final IntSupplier maxInFlightSupplier;
    private final MetricsPort metrics;
    private final Object lock = new Object();
    private final Queue<TrackingObservationUseCase.Request> queued = new ArrayDeque<>();

    private int inFlight;
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
        boolean dispatch;
        synchronized (lock) {
            if (closed) {
                metrics.increment("tracking.rejected");
                return false;
            }
            if (inFlight < currentMaxInFlight()) {
                inFlight++;
                dispatch = true;
            } else if (queued.size() < currentMaxQueued()) {
                queued.add(request);
                dispatch = false;
            } else {
                metrics.increment("tracking.rejected");
                updateGauges();
                plugin.getLogger().warning(
                        "Lore-item tracking backlog is full; previous durable evidence was preserved.");
                return false;
            }
            metrics.increment("tracking.accepted");
            updateGauges();
        }
        if (dispatch) {
            start(request);
        }
        return true;
    }

    private void start(TrackingObservationUseCase.Request request) {
        long startedAt = System.nanoTime();
        CompletionStage<TrackingObservationUseCase.Result> stage;
        try {
            TrackingObservationUseCase useCase = Objects.requireNonNull(
                    useCaseSupplier.get(), "tracking use case supplier returned null");
            stage = Objects.requireNonNull(
                    useCase.record(request), "tracking use case returned null");
        } catch (RuntimeException exception) {
            metrics.increment("tracking.failed");
            plugin.getLogger().log(Level.SEVERE, "Could not start lore-item tracking.", exception);
            finish();
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
            finish();
        });
    }

    private void finish() {
        Optional<TrackingObservationUseCase.Request> next = Optional.empty();
        synchronized (lock) {
            inFlight--;
            if (!closed && !queued.isEmpty() && inFlight < currentMaxInFlight()) {
                next = Optional.of(queued.remove());
                inFlight++;
            }
            updateGauges();
        }
        next.ifPresent(this::start);
    }

    private int currentMaxInFlight() {
        int value = maxInFlightSupplier.getAsInt();
        if (value < MIN_CAPACITY) {
            throw new IllegalStateException("Configured tracking budget must be positive");
        }
        return value;
    }

    private int currentMaxQueued() {
        return Math.multiplyExact(currentMaxInFlight(), QUEUE_MULTIPLIER);
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
        List<TrackingObservationUseCase.Request> pending;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            pending = new ArrayList<>(queued);
            queued.clear();
            inFlight = Math.addExact(inFlight, pending.size());
            updateGauges();
        }
        pending.forEach(this::start);
    }
}
