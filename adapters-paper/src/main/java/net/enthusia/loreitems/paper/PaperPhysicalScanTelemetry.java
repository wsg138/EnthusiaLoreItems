package net.enthusia.loreitems.paper;

import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.enthusia.loreitems.application.MetricsPort;
import org.bukkit.plugin.Plugin;

/** Fixed-category main-thread scan accounting; never influences queue decisions. */
final class PaperPhysicalScanTelemetry {
    private final MetricsPort metrics;
    private boolean saturated;
    private long scanFailures;

    PaperPhysicalScanTelemetry(MetricsPort metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    void updateDepth(int queued, int periodic) {
        metrics.setGauge("tracking.scan_backlog", queued);
        metrics.setGauge("tracking.scan_backlog_peak", queued);
        metrics.setGauge("tracking.scan_periodic_backlog", periodic);
        metrics.setGauge("tracking.scan_event_backlog", queued - periodic);
    }

    void saturated(Logger logger) {
        if (!saturated) {
            saturated = true;
            logger.warning(
                    "Lore-item scan backlog is full; previous durable evidence was preserved.");
        }
    }

    void drained(boolean empty, Logger logger) {
        if (empty && saturated) {
            saturated = false;
            logger.fine("Lore-item scan backlog has drained.");
        }
    }

    void reset() {
        saturated = false;
    }

    void reject(PaperTrackingScanRequest request) {
        metrics.increment("tracking.rejected");
        if (request.periodicChunkKey() != null) {
            metrics.increment("tracking.scan_periodic_rejected");
        } else {
            metrics.increment("tracking.scan_event_rejected");
            metrics.increment(request.playerId() != null
                    ? "tracking.scan_player_rejected" : "tracking.scan_chunk_rejected");
        }
    }

    void execute(
            PaperTrackingScanRequest request,
            Plugin plugin,
            PaperPhysicalTrackingListener listener) {
        boolean periodic = request.periodicChunkKey() != null;
        try {
            boolean scanned = runTimed(request, plugin, listener, periodic);
            metrics.increment(periodic
                    ? (scanned ? "tracking.scan_periodic_completed" : "tracking.scan_periodic_skipped")
                    : (scanned ? "tracking.scan_event_completed" : "tracking.scan_event_skipped"));
        } catch (RuntimeException failure) {
            metrics.increment(periodic
                    ? "tracking.scan_periodic_failed" : "tracking.scan_event_failed");
            scanFailures++;
            if (scanFailures == 1L || scanFailures % 100L == 0L) {
                plugin.getLogger().log(Level.WARNING,
                        "LoreItems queued scan failed for " + request.source()
                                + "; prior durable evidence retained, current scan incomplete.",
                        failure);
            }
        }
    }

    private boolean runTimed(PaperTrackingScanRequest request, Plugin plugin,
            PaperPhysicalTrackingListener listener, boolean periodic) {
        long started = System.nanoTime();
        try {
            return request.run(plugin, listener);
        } finally {
            // Capture scan cost before any warning/stack-trace I/O.
            recordElapsed(request, periodic, System.nanoTime() - started);
        }
    }

    private void recordElapsed(PaperTrackingScanRequest request, boolean periodic, long elapsed) {
        metrics.recordDurationNanos(
                periodic ? "tracking.scan_periodic_nanos" : "tracking.scan_event_nanos", elapsed);
        metrics.setGauge(periodic ? "tracking.scan_periodic_peak_nanos"
                : request.playerId() != null ? "tracking.scan_player_peak_nanos"
                : "tracking.scan_chunk_peak_nanos", elapsed);
    }
}
