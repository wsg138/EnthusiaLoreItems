package net.enthusia.loreitems.paper;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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
    private static final int NATURAL_ACCESS_DEBOUNCE_CAPACITY = 4_096;
    private static final long NATURAL_ACCESS_DEBOUNCE_MILLIS = 250L;
    private static final String NATURAL_ACCESS_SUFFIX = "-unique";
    private static final Object REGISTRY_LOCK = new Object();
    @SuppressWarnings("PMD.UseConcurrentHashMap") // Guarded by REGISTRY_LOCK; plugin keys require identity semantics.
    private static final Map<Plugin, Set<PaperTrackingCoordinator>> ACTIVE_COORDINATORS =
            new IdentityHashMap<>();

    private final Plugin plugin;
    private final Supplier<TrackingObservationUseCase> useCaseSupplier;
    private final IntSupplier maxInFlightSupplier;
    private final MetricsPort metrics;
    private final BoundedDebounceRegistry<DebounceKey> naturalAccessDebounce;
    private final Object lock = new Object();
    private final Queue<PendingObservation> queued = new ArrayDeque<>();
    private final CompletableFuture<Void> quiesced = new CompletableFuture<>();
    private final PaperDurableTrackingRetryLedger unresolved = new PaperDurableTrackingRetryLedger();
    private final AtomicLong submissionOrder = new AtomicLong();
    private final Object acknowledgementIssuer = new Object();

    private int inFlight;
    private boolean saturated;
    private boolean invalidBudgetReported;
    private boolean closed;

    public PaperTrackingCoordinator(
            Plugin plugin,
            Supplier<TrackingObservationUseCase> useCaseSupplier,
            IntSupplier maxInFlightSupplier,
            MetricsPort metrics) {
        this(
                plugin,
                useCaseSupplier,
                maxInFlightSupplier,
                metrics,
                new BoundedDebounceRegistry<>(
                        Clock.systemUTC(),
                        Duration.ofMillis(NATURAL_ACCESS_DEBOUNCE_MILLIS),
                        NATURAL_ACCESS_DEBOUNCE_CAPACITY));
    }

    PaperTrackingCoordinator(
            Plugin plugin,
            Supplier<TrackingObservationUseCase> useCaseSupplier,
            IntSupplier maxInFlightSupplier,
            MetricsPort metrics,
            BoundedDebounceRegistry<DebounceKey> naturalAccessDebounce) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.useCaseSupplier = Objects.requireNonNull(useCaseSupplier, "useCaseSupplier");
        this.maxInFlightSupplier = Objects.requireNonNull(
                maxInFlightSupplier, "maxInFlightSupplier");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.naturalAccessDebounce = Objects.requireNonNull(
                naturalAccessDebounce, "naturalAccessDebounce");
        currentMaxInFlight();
        registerCoordinator();
    }

    /**
     * Returns a barrier for every accepted Paper-side observation currently owned by the plugin.
     * Tracking coordinators, identity-anomaly reporters, and display persistence listeners remain
     * registered after close until accepted durable work reaches quiescence, allowing shutdown to
     * preserve ordering without blocking the Paper primary thread.
     */
    public static CompletionStage<Void> quiescenceFor(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        CompletableFuture<?>[] barriers;
        synchronized (REGISTRY_LOCK) {
            Set<PaperTrackingCoordinator> coordinators = ACTIVE_COORDINATORS.get(plugin);
            barriers = coordinators == null
                    ? new CompletableFuture<?>[0]
                    : coordinators.stream()
                            .map(coordinator -> coordinator.quiesced)
                            .toArray(CompletableFuture[]::new);
        }
        CompletionStage<Void> tracking = CompletableFuture.allOf(barriers);
        CompletionStage<Void> anomalies = PaperItemAnomalyReporter.quiescenceFor(plugin);
        CompletionStage<Void> displays = PaperDisplayItemListener.quiescenceFor(plugin);
        return CompletableFuture.allOf(
                tracking.toCompletableFuture(),
                anomalies.toCompletableFuture(),
                displays.toCompletableFuture());
    }

    private void registerCoordinator() {
        synchronized (REGISTRY_LOCK) {
            ACTIVE_COORDINATORS
                    .computeIfAbsent(plugin, ignored -> new HashSet<>())
                    .add(this);
        }
        quiesced.whenComplete((ignored, failure) -> unregisterCoordinator());
    }

    private void unregisterCoordinator() {
        synchronized (REGISTRY_LOCK) {
            Set<PaperTrackingCoordinator> coordinators = ACTIVE_COORDINATORS.get(plugin);
            if (coordinators == null) {
                return;
            }
            coordinators.remove(this);
            if (coordinators.isEmpty()) {
                ACTIVE_COORDINATORS.remove(plugin);
            }
        }
    }

    /** Admission is not a durable-write acknowledgement. */
    public boolean submit(TrackingObservationUseCase.Request request) {
        Objects.requireNonNull(request, "request");
        return submitInternal(request, submissionOrder.incrementAndGet(), null);
    }

    /** Use this API when a caller must distinguish queue admission from database settlement. */
    public PaperTrackingAcknowledgement submitAcknowledged(
            TrackingObservationUseCase.Request request) {
        Objects.requireNonNull(request, "request");
        long sequence = submissionOrder.incrementAndGet();
        CompletableFuture<PaperTrackingAcknowledgement.Settlement> future =
                new CompletableFuture<>();
        boolean accepted = submitInternal(request, sequence, future);
        return PaperTrackingAcknowledgement.issued(
                accepted, future, request, sequence, acknowledgementIssuer);
    }

    /** Dirty holders require NEW naturally observed evidence; stale transitions are never replayed. */
    public boolean needsReconciliation(TrackingObservationUseCase.Request request) {
        return unresolved.needsReconciliation(Objects.requireNonNull(request, "request"));
    }

    public DurabilitySnapshot durabilitySnapshot() {
        PaperDurableTrackingRetryLedger.Snapshot snapshot = unresolved.snapshot();
        return new DurabilitySnapshot(
                snapshot.unresolvedHolders(), snapshot.overflow(), snapshot.overflowedKeys());
    }

    /**
     * Completeness is asserted only by the natural-load scanner after full inventory coverage.
     * One recorded item alone never clears dirty-holder uncertainty.
     */
    public CompletionStage<Boolean> confirmCompleteHolderReconciliation(
            TrackingObservationUseCase.Request holder,
            boolean completeCoverage,
            List<PaperTrackingAcknowledgement> observations) {
        return PaperTrackingAcknowledgementVerifier.confirm(
                unresolved, holder, completeCoverage, observations, acknowledgementIssuer)
                .thenApply(recovered -> {
                    if (recovered) {
                        metrics.increment("tracking.persistence_recovered");
                        updateDurabilityGauges();
                    }
                    return recovered;
                });
    }

    private boolean submitInternal(
            TrackingObservationUseCase.Request request, long sequence,
            CompletableFuture<PaperTrackingAcknowledgement.Settlement> acknowledgement) {
        if (rejectIfClosed()) {
            markUnresolved(request, sequence);
            settle(acknowledgement, PaperTrackingAcknowledgement.Settlement.REJECTED);
            return false;
        }
        if (coalesceNaturalAccess(request)) {
            return coalescedSubmission(request, sequence, acknowledgement);
        }
        Optional<PendingObservation> resolved = resolve(request, sequence, acknowledgement);
        if (resolved.isEmpty()) {
            return false;
        }
        PendingObservation observation = resolved.orElseThrow();
        Submission submission = reserve(observation);
        if (submission == Submission.REJECTED) {
            markUnresolved(request, sequence);
            naturalAccessDebounce.clear();
            settle(acknowledgement, PaperTrackingAcknowledgement.Settlement.REJECTED);
            return false;
        }
        recordFreshReobservation(request);
        if (submission == Submission.DISPATCH) {
            startCounted(observation);
        }
        return true;
    }

    private boolean coalescedSubmission(
            TrackingObservationUseCase.Request request, long sequence,
            CompletableFuture<PaperTrackingAcknowledgement.Settlement> acknowledgement) {
        if (rejectIfClosed()) {
            markUnresolved(request, sequence);
            settle(acknowledgement, PaperTrackingAcknowledgement.Settlement.REJECTED);
            return false;
        }
        settle(acknowledgement, PaperTrackingAcknowledgement.Settlement.COALESCED_UNVERIFIED);
        return true;
    }

    private void recordFreshReobservation(TrackingObservationUseCase.Request request) {
        if (unresolved.needsReconciliation(request)
                && request.mode() == TrackingObservationUseCase.EvidenceMode.RECONCILIATION
                && request.presence() == TrackingObservationUseCase.Presence.PRESENT) {
            metrics.increment("tracking.persistence_reobservations");
        }
    }

    private boolean coalesceNaturalAccess(TrackingObservationUseCase.Request request) {
        if (!request.source().endsWith(NATURAL_ACCESS_SUFFIX)
                || unresolved.needsReconciliation(request)) {
            return false;
        }
        BoundedDebounceRegistry.OfferResult result = naturalAccessDebounce.offer(
                DebounceKey.from(request));
        metrics.setGauge("tracking.debounce.size", naturalAccessDebounce.size());
        if (result == BoundedDebounceRegistry.OfferResult.DUPLICATE) {
            metrics.increment("tracking.debounce.coalesced");
            return true;
        }
        if (result == BoundedDebounceRegistry.OfferResult.ACCEPTED_AFTER_EVICTION) {
            metrics.increment("tracking.debounce.evicted");
        }
        return false;
    }

    private boolean rejectIfClosed() {
        synchronized (lock) {
            if (!closed) {
                return false;
            }
            metrics.increment("tracking.rejected");
            return true;
        }
    }

    private Optional<PendingObservation> resolve(
            TrackingObservationUseCase.Request request, long sequence,
            CompletableFuture<PaperTrackingAcknowledgement.Settlement> acknowledgement) {
        try {
            return Optional.of(new PendingObservation(
                    Objects.requireNonNull(
                            useCaseSupplier.get(), "tracking use case supplier returned null"),
                    request, sequence, acknowledgement));
        } catch (RuntimeException exception) {
            fail(request, sequence, acknowledgement, exception);
            return Optional.empty();
        }
    }

    private Submission reserve(PendingObservation observation) {
        synchronized (lock) {
            if (closed) {
                metrics.increment("tracking.rejected");
                return Submission.REJECTED;
            }
            int maxInFlight = completionMaxInFlight();
            int maxQueued = maxInFlight > Integer.MAX_VALUE / QUEUE_MULTIPLIER
                    ? Integer.MAX_VALUE : maxInFlight * QUEUE_MULTIPLIER;
            if (inFlight < maxInFlight) {
                inFlight++;
                accept();
                return Submission.DISPATCH;
            }
            if (queued.size() < maxQueued) {
                queued.add(observation);
                if (queued.size() == maxQueued) {
                    reportSaturation();
                }
                accept();
                return Submission.QUEUED;
            }
            metrics.increment("tracking.rejected");
            updateGauges();
            reportSaturation();
            return Submission.REJECTED;
        }
    }

    private void accept() {
        metrics.increment("tracking.accepted");
        updateGauges();
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
        AtomicBoolean finished = new AtomicBoolean();
        try {
            CompletionStage<TrackingObservationUseCase.Result> stage =
                    Objects.requireNonNull(
                            observation.useCase().record(observation.request()),
                            "tracking use case returned null stage");
            stage.whenComplete((result, failure) ->
                    finish(observation, startedAt, result, failure, finished));
        } catch (RuntimeException exception) {
            finish(observation, startedAt, null, exception, finished);
        }
    }

    private void finish(
            PendingObservation observation, long startedAt,
            TrackingObservationUseCase.Result result, Throwable failure,
            AtomicBoolean finished) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        try {
            metrics.recordDurationNanos(
                    "tracking.persistence_nanos", System.nanoTime() - startedAt);
            if (failure != null || result == null) {
                Throwable cause = failure != null
                        ? unwrap(failure) : new IllegalStateException("Null persistence result");
                fail(observation.request(), observation.sequence(),
                        observation.acknowledgement(), cause);
            } else {
                acknowledgeResult(observation, result);
            }
        } finally {
            completeCountedRequest();
        }
    }

    private void acknowledgeResult(
            PendingObservation observation, TrackingObservationUseCase.Result result) {
        metrics.increment("tracking.completed");
        switch (result.status()) {
            case RECORDED, UNCHANGED -> acknowledgeConfirmed(observation);
            case CONFLICT_RECORDED -> {
                metrics.increment("tracking.conflicts");
                settle(observation.acknowledgement(),
                        PaperTrackingAcknowledgement.Settlement.CONFLICT_RECORDED);
            }
            default -> {
                if (result.status() == TrackingObservationUseCase.Status.SERVICE_UNAVAILABLE) {
                    markUnresolved(observation.request(), observation.sequence());
                }
                metrics.increment("tracking.persistence_not_recorded");
                settle(observation.acknowledgement(),
                        PaperTrackingAcknowledgement.Settlement.NOT_RECORDED);
            }
        }
    }

    private void acknowledgeConfirmed(PendingObservation observation) {
        metrics.increment("tracking.persistence_confirmed");
        settle(observation.acknowledgement(),
                PaperTrackingAcknowledgement.Settlement.CONFIRMED);
    }

    private void fail(
            TrackingObservationUseCase.Request request, long sequence,
            CompletableFuture<PaperTrackingAcknowledgement.Settlement> acknowledgement,
            Throwable failure) {
        metrics.increment("tracking.failed");
        markUnresolved(request, sequence);
        naturalAccessDebounce.clear();
        plugin.getLogger().log(Level.SEVERE,
                "Lore-item persistence failed; holder requires fresh natural reconciliation.",
                failure);
        settle(acknowledgement, PaperTrackingAcknowledgement.Settlement.FAILED);
    }

    private void markUnresolved(TrackingObservationUseCase.Request request, long sequence) {
        if (unresolved.markUnresolved(request, sequence)) {
            plugin.getLogger().severe("Tracking dirty-holder capacity exceeded: "
                    + "global uncertainty is sticky until independent reconciliation.");
        }
        metrics.increment("tracking.persistence_unresolved");
        updateDurabilityGauges();
    }

    private void updateDurabilityGauges() {
        PaperDurableTrackingRetryLedger.Snapshot snapshot = unresolved.snapshot();
        metrics.setGauge("tracking.persistence_unresolved_holders",
                snapshot.unresolvedHolders());
        metrics.setGauge("tracking.persistence_overflow", snapshot.overflow() ? 1L : 0L);
    }

    private static void settle(
            CompletableFuture<PaperTrackingAcknowledgement.Settlement> acknowledgement,
            PaperTrackingAcknowledgement.Settlement status) {
        if (acknowledgement != null) {
            acknowledgement.complete(status);
        }
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
        naturalAccessDebounce.clear();
        metrics.setGauge("tracking.debounce.size", 0L);
    }

    record DebounceKey(
            net.enthusia.loreitems.application.LoreItemIdentity identity,
            net.enthusia.loreitems.domain.LocationDescriptor location,
            TrackingObservationUseCase.Presence presence,
            TrackingObservationUseCase.EvidenceMode mode) {
        DebounceKey {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(presence, "presence");
            Objects.requireNonNull(mode, "mode");
        }

        static DebounceKey from(TrackingObservationUseCase.Request request) {
            return new DebounceKey(
                    request.identity(), request.location(), request.presence(), request.mode());
        }
    }

    private enum Submission {
        DISPATCH,
        QUEUED,
        REJECTED
    }

    public record DurabilitySnapshot(
            int unresolvedHolders, boolean overflow, long overflowedKeys) {}

    private record PendingObservation(
            TrackingObservationUseCase useCase,
            TrackingObservationUseCase.Request request,
            long sequence,
            CompletableFuture<PaperTrackingAcknowledgement.Settlement> acknowledgement) {
        private PendingObservation {
            Objects.requireNonNull(useCase, "useCase");
            Objects.requireNonNull(request, "request");
        }
    }
}
