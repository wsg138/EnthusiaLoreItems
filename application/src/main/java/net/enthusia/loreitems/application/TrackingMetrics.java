package net.enthusia.loreitems.application;

import java.util.concurrent.atomic.AtomicLong;

/** Small process-local metrics snapshot for operator visibility and backpressure diagnostics. */
public final class TrackingMetrics implements MetricsPort {
    private final AtomicLong queued = new AtomicLong();
    private final AtomicLong inFlight = new AtomicLong();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong conflicts = new AtomicLong();
    private final AtomicLong durationNanos = new AtomicLong();

    @Override
    public void setGauge(String name, long value) {
        switch (name) {
            case "tracking.queued" -> queued.set(value);
            case "tracking.in_flight" -> inFlight.set(value);
            default -> {
                // Metrics outside this phase are intentionally ignored by this focused port.
            }
        }
    }

    @Override
    public void increment(String name) {
        switch (name) {
            case "tracking.accepted" -> accepted.incrementAndGet();
            case "tracking.rejected" -> rejected.incrementAndGet();
            case "tracking.completed" -> completed.incrementAndGet();
            case "tracking.failed" -> failed.incrementAndGet();
            case "tracking.conflicts" -> conflicts.incrementAndGet();
            default -> {
                // Metrics outside this phase are intentionally ignored by this focused port.
            }
        }
    }

    @Override
    public void recordDurationNanos(String name, long value) {
        if ("tracking.persistence_nanos".equals(name) && value >= 0L) {
            durationNanos.addAndGet(value);
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(
                queued.get(),
                inFlight.get(),
                accepted.get(),
                rejected.get(),
                completed.get(),
                failed.get(),
                conflicts.get(),
                durationNanos.get());
    }

    public record Snapshot(
            long queued,
            long inFlight,
            long accepted,
            long rejected,
            long completed,
            long failed,
            long conflicts,
            long persistenceNanos) {}
}
