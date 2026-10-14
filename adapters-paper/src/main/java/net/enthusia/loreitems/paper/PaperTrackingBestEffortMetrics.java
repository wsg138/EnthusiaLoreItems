package net.enthusia.loreitems.paper;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.enthusia.loreitems.application.MetricsPort;

/** Telemetry must never prevent DB result settlement, queue accounting or recovery. */
final class PaperTrackingBestEffortMetrics implements MetricsPort {
    private final MetricsPort delegate;
    private final Logger logger;
    private final AtomicBoolean warned = new AtomicBoolean();

    PaperTrackingBestEffortMetrics(MetricsPort delegate, Logger logger) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public void setGauge(String name, long value) {
        try {
            delegate.setGauge(name, value);
        } catch (RuntimeException failure) {
            report(failure);
        }
    }

    @Override
    public void increment(String name) {
        try {
            delegate.increment(name);
        } catch (RuntimeException failure) {
            report(failure);
        }
    }

    @Override
    public void recordDurationNanos(String name, long value) {
        try {
            delegate.recordDurationNanos(name, value);
        } catch (RuntimeException failure) {
            report(failure);
        }
    }

    private void report(RuntimeException failure) {
        if (warned.compareAndSet(false, true)) {
            logger.log(Level.WARNING, "Tracking telemetry failed; evidence processing continues.",
                    failure);
        }
    }
}
