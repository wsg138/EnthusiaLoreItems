package net.enthusia.loreitems.application;

import java.util.concurrent.atomic.AtomicLong;

/** Small process-local metrics snapshot for operator visibility and backpressure diagnostics. */
public final class TrackingMetrics implements MetricsPort {
    private final AtomicLong queued = new AtomicLong();
    private final AtomicLong inFlight = new AtomicLong();
    private final AtomicLong additionalQueued = new AtomicLong();
    private final AtomicLong additionalInFlight = new AtomicLong();
    private final AtomicLong scanBacklog = new AtomicLong();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong conflicts = new AtomicLong();
    private final AtomicLong durationNanos = new AtomicLong();
    private final MetricsPort additionalQueueView = new MetricsPort() {
        @Override
        public void setGauge(String name, long value) {
            switch (name) {
                case "tracking.queued" ->
                        TrackingMetrics.this.setGauge("tracking.additional.queued", value);
                case "tracking.in_flight" ->
                        TrackingMetrics.this.setGauge("tracking.additional.in_flight", value);
                default -> TrackingMetrics.this.setGauge(name, value);
            }
        }

        @Override
        public void increment(String name) {
            TrackingMetrics.this.increment(name);
        }

        @Override
        public void recordDurationNanos(String name, long persistenceNanos) {
            TrackingMetrics.this.recordDurationNanos(name, persistenceNanos);
        }
    };

    @Override
    public void setGauge(String name, long value) {
        switch (name) {
            case "tracking.queued" -> queued.set(value);
            case "tracking.in_flight" -> inFlight.set(value);
            case "tracking.additional.queued" -> additionalQueued.set(value);
            case "tracking.additional.in_flight" -> additionalInFlight.set(value);
            case "tracking.scan_backlog" -> scanBacklog.set(value);
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

    public MetricsPort additionalQueueView() {
        return additionalQueueView;
    }

    public Snapshot snapshot() {
        return new Snapshot(
                queued.get() + additionalQueued.get(),
                inFlight.get() + additionalInFlight.get(),
                scanBacklog.get(),
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
            long scanBacklog,
            long accepted,
            long rejected,
            long completed,
            long failed,
            long conflicts,
            long persistenceNanos) {}
}
