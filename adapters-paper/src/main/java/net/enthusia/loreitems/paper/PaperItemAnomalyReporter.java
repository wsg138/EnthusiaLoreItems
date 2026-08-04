package net.enthusia.loreitems.paper;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.logging.Level;
import net.enthusia.loreitems.application.AnomalyWarningSink;
import net.enthusia.loreitems.application.ItemAnomalyObservationUseCase;
import net.enthusia.loreitems.application.ItemIdentityReadResult;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.domain.LocationDescriptor;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

// Compound queue, cooldown, and in-flight transitions are guarded by lock; independent concurrent
// maps would not make those multi-collection invariants atomic.
@SuppressWarnings("PMD.UseConcurrentHashMap")
final class PaperItemAnomalyReporter implements AutoCloseable {
    private static final Duration REPORT_COOLDOWN = Duration.ofSeconds(5);
    private static final int CAPACITY_MULTIPLIER = 4;
    private static final int MIN_IN_FLIGHT = 1;

    private final Plugin plugin;
    private final PaperItemIdentityCodec identityCodec = new PaperItemIdentityCodec();
    private final int maxInFlight;
    private final int maxCooldowns;
    private final int maxPending;
    private final Object lock = new Object();
    private final Set<ReportKey> inFlight = new HashSet<>();
    private final Map<ReportKey, Long> retryAfterNanos = new HashMap<>();
    private final LinkedHashMap<ReportKey, PendingReport> pending = new LinkedHashMap<>();
    private final ThreadLocal<ArrayDeque<PendingReport>> dispatchQueue =
            ThreadLocal.withInitial(ArrayDeque::new);
    private final ThreadLocal<Boolean> dispatching =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    private boolean overflowLogged;
    private boolean closed;

    PaperItemAnomalyReporter(Plugin plugin, int maxInFlight) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        if (maxInFlight < MIN_IN_FLIGHT) {
            throw new IllegalArgumentException("maxInFlight must be positive");
        }
        this.maxInFlight = maxInFlight;
        this.maxCooldowns = Math.multiplyExact(maxInFlight, CAPACITY_MULTIPLIER);
        this.maxPending = Math.multiplyExact(maxInFlight, CAPACITY_MULTIPLIER);
    }

    ItemIdentityReadResult inspect(
            ItemStack item,
            LocationDescriptor location,
            String source) {
        ItemIdentityReadResult result = identityCodec.readIdentity(item);
        if (result instanceof ItemIdentityReadResult.Invalid invalid
                && invalid.identityEvidence() != null) {
            record(
                    ItemAnomalyObservationUseCase.Kind.MALFORMED_STACK,
                    invalid.identityEvidence(),
                    location,
                    List.of(location),
                    source,
                    invalid.failure().name() + ": " + invalid.detail());
        }
        return result;
    }

    void recordDuplicate(
            LoreItemIdentity identity,
            LocationDescriptor location,
            List<LocationDescriptor> evidenceLocations,
            String source,
            String detail) {
        record(
                ItemAnomalyObservationUseCase.Kind.DUPLICATE_INSTANCE,
                identity,
                location,
                evidenceLocations,
                source,
                detail);
    }

    private void record(
            ItemAnomalyObservationUseCase.Kind kind,
            LoreItemIdentity identity,
            LocationDescriptor location,
            List<LocationDescriptor> evidenceLocations,
            String source,
            String detail) {
        ItemAnomalyObservationUseCase useCase = plugin.getServer()
                .getServicesManager()
                .load(ItemAnomalyObservationUseCase.class);
        if (useCase == null) {
            return;
        }
        AnomalyWarningSink warningSink = plugin.getServer()
                .getServicesManager()
                .load(AnomalyWarningSink.class);
        ReportKey key = new ReportKey(
                kind,
                identity.instanceId().value(),
                location.type(),
                location.locationKey(),
                location.containerPath());
        ItemAnomalyObservationUseCase.Request request =
                new ItemAnomalyObservationUseCase.Request(
                        kind,
                        identity,
                        location,
                        evidenceLocations,
                        source,
                        detail);
        PendingReport report = new PendingReport(key, request, useCase, warningSink);
        Submission submission = submit(report);
        if (submission == Submission.START) {
            dispatch(report);
        } else if (submission == Submission.OVERFLOW) {
            plugin.getLogger().warning(
                    "Lore-item anomaly evidence queue is full; the newest observation was not "
                            + "persisted and existing durable evidence was left unchanged.");
        }
    }

    private Submission submit(PendingReport report) {
        synchronized (lock) {
            if (closed) {
                return Submission.IGNORED;
            }
            ReportKey key = report.key();
            Long retryAt = retryAfterNanos.get(key);
            long now = System.nanoTime();
            if (retryAt != null) {
                if (retryAt > now) {
                    return Submission.IGNORED;
                }
                retryAfterNanos.remove(key);
            }
            if (pending.containsKey(key)) {
                pending.put(key, report);
                return Submission.QUEUED;
            }
            if (inFlight.contains(key)) {
                return queue(report);
            }
            if (inFlight.size() < maxInFlight) {
                inFlight.add(key);
                return Submission.START;
            }
            return queue(report);
        }
    }

    private Submission queue(PendingReport report) {
        if (pending.size() >= maxPending) {
            if (overflowLogged) {
                return Submission.IGNORED;
            }
            overflowLogged = true;
            return Submission.OVERFLOW;
        }
        pending.put(report.key(), report);
        return Submission.QUEUED;
    }

    private void dispatch(PendingReport report) {
        ArrayDeque<PendingReport> queue = dispatchQueue.get();
        queue.addLast(report);
        if (dispatching.get()) {
            return;
        }
        dispatching.set(Boolean.TRUE);
        try {
            while (true) {
                PendingReport next = queue.pollFirst();
                if (next == null) {
                    break;
                }
                startOne(next);
            }
        } finally {
            queue.clear();
            dispatching.remove();
            dispatchQueue.remove();
        }
    }

    private void startOne(PendingReport report) {
        CompletionStage<ItemAnomalyObservationUseCase.Result> stage;
        try {
            stage = Objects.requireNonNull(
                    report.useCase().record(report.request()),
                    "identity anomaly persistence returned null stage");
        } catch (RuntimeException exception) {
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Could not start lore-item identity anomaly persistence.",
                    exception);
            finishAndDrain(report.key());
            return;
        }
        stage.whenComplete((result, failure) -> {
            if (failure != null) {
                plugin.getLogger().log(
                        Level.SEVERE,
                        "Could not persist lore-item identity anomaly evidence.",
                        unwrap(failure));
            } else if (result == null) {
                plugin.getLogger().severe(
                        "Lore-item identity anomaly persistence returned no result.");
            } else if (result.shouldWarnStaff() && report.warningSink() != null) {
                report.warningSink().requestWarning();
            }
            finishAndDrain(report.key());
        });
    }

    private void finishAndDrain(ReportKey completedKey) {
        Optional<PendingReport> next;
        synchronized (lock) {
            inFlight.remove(completedKey);
            if (!closed && !pending.containsKey(completedKey)
                    && retryAfterNanos.size() < maxCooldowns) {
                retryAfterNanos.put(
                        completedKey,
                        System.nanoTime() + REPORT_COOLDOWN.toNanos());
            }
            next = closed ? Optional.empty() : pollPending();
            next.ifPresent(report -> inFlight.add(report.key()));
            if (pending.size() < maxPending) {
                overflowLogged = false;
            }
        }
        next.ifPresent(this::dispatch);
    }

    private Optional<PendingReport> pollPending() {
        Iterator<Map.Entry<ReportKey, PendingReport>> iterator =
                pending.entrySet().iterator();
        if (!iterator.hasNext()) {
            return Optional.empty();
        }
        PendingReport report = iterator.next().getValue();
        iterator.remove();
        return Optional.of(report);
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
            inFlight.clear();
            pending.clear();
            retryAfterNanos.clear();
        }
    }

    private enum Submission {
        START,
        QUEUED,
        OVERFLOW,
        IGNORED
    }

    private record PendingReport(
            ReportKey key,
            ItemAnomalyObservationUseCase.Request request,
            ItemAnomalyObservationUseCase useCase,
            AnomalyWarningSink warningSink) {
        private PendingReport {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(useCase, "useCase");
        }
    }

    private record ReportKey(
            ItemAnomalyObservationUseCase.Kind kind,
            java.util.UUID instanceId,
            LocationDescriptor.Type locationType,
            String locationKey,
            String containerPath) {}
}
