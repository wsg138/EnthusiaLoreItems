package net.enthusia.loreitems.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TrackingMetricsTest {
    @Test
    void aggregatesIndependentQueueViewsWhileSharingCounters() {
        TrackingMetrics metrics = new TrackingMetrics();
        MetricsPort additional = metrics.additionalQueueView();

        metrics.setGauge("tracking.queued", 3L);
        metrics.setGauge("tracking.in_flight", 2L);
        additional.setGauge("tracking.queued", 5L);
        additional.setGauge("tracking.in_flight", 4L);
        metrics.setGauge("tracking.scan_backlog", 7L);
        metrics.increment("tracking.accepted");
        additional.increment("tracking.accepted");
        additional.increment("tracking.failed");
        metrics.increment("tracking.scan_truncated");
        additional.recordDurationNanos("tracking.persistence_nanos", 11L);

        TrackingMetrics.Snapshot snapshot = metrics.snapshot();
        assertEquals(8L, snapshot.queued());
        assertEquals(6L, snapshot.inFlight());
        assertEquals(7L, snapshot.scanBacklog());
        assertEquals(2L, snapshot.accepted());
        assertEquals(1L, snapshot.failed());
        assertEquals(1L, snapshot.scanTruncated());
        assertEquals(11L, snapshot.persistenceNanos());
    }
}
