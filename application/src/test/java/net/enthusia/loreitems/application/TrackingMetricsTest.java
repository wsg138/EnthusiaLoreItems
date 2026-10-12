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
        metrics.setGauge("tracking.scan_backlog_peak", 7L);
        metrics.setGauge("tracking.scan_backlog_peak", 2L);
        additional.setGauge("tracking.scan_backlog_peak", 10L);
        metrics.setGauge("tracking.scan_event_backlog", 4L);
        metrics.setGauge("tracking.scan_periodic_backlog", 3L);
        metrics.increment("tracking.accepted");
        additional.increment("tracking.accepted");
        additional.increment("tracking.failed");
        metrics.increment("tracking.scan_truncated");
        metrics.increment("tracking.deferred_player_scan_coalesced");
        additional.increment("tracking.deferred_player_scan_coalesced");
        metrics.increment("tracking.periodic_chunk_scan_coalesced");
        additional.increment("tracking.deferred_player_scan_schedule_rejected");
        additional.recordDurationNanos("tracking.persistence_nanos", 11L);
        metrics.increment("tracking.scan_event_rejected");
        additional.increment("tracking.scan_periodic_rejected");
        metrics.increment("tracking.scan_event_completed");
        additional.increment("tracking.scan_periodic_completed");
        additional.recordDurationNanos("tracking.scan_event_nanos", 41L);
        metrics.recordDurationNanos("tracking.scan_periodic_nanos", 17L);
        metrics.recordDurationNanos("tracking.scan_event_nanos", -10L);
        metrics.increment("tracking.hopper_move_events");
        additional.increment("tracking.hopper_move_deferred");
        metrics.recordDurationNanos("tracking.hopper_move_event_nanos", 21L);
        additional.recordDurationNanos("tracking.hopper_move_deferred_nanos", 31L);
        metrics.recordDurationNanos("tracking.hopper_move_event_nanos", -5L);
        metrics.setGauge("tracking.hopper_move_event_peak_nanos", 2100000L);
        additional.setGauge("tracking.hopper_move_event_peak_nanos", 1400000L);
        metrics.setGauge("tracking.hopper_move_deferred_peak_nanos", 3100000L);
        metrics.setGauge("tracking.hopper_move_deferred_peak_nanos", -50L);

        TrackingMetrics.Snapshot snapshot = metrics.snapshot();
        assertEquals(8L, snapshot.queued());
        assertEquals(6L, snapshot.inFlight());
        assertEquals(7L, snapshot.scanBacklog());
        assertEquals(2L, snapshot.accepted());
        assertEquals(1L, snapshot.failed());
        assertEquals(1L, snapshot.scanTruncated());
        assertEquals(11L, snapshot.persistenceNanos());
        assertEquals(2L, snapshot.deferredPlayerScanCoalesced());
        assertEquals(1L, snapshot.periodicChunkScanCoalesced());
        assertEquals(1L, snapshot.deferredPlayerScanScheduleRejected());
        assertEquals(10L, snapshot.scanBacklogPeak());
        assertEquals(4L, snapshot.scanEventBacklog());
        assertEquals(3L, snapshot.scanPeriodicBacklog());
        assertEquals(1L, snapshot.scanEventRejected());
        assertEquals(1L, snapshot.scanPeriodicRejected());
        assertEquals(1L, snapshot.scanEventCompleted());
        assertEquals(1L, snapshot.scanPeriodicCompleted());
        assertEquals(41L, snapshot.scanEventNanos());
        assertEquals(17L, snapshot.scanPeriodicNanos());
        assertEquals(1L, snapshot.hopperMoveEvents());
        assertEquals(1L, snapshot.hopperMoveDeferred());
        assertEquals(21L, snapshot.hopperMoveEventNanos());
        assertEquals(31L, snapshot.hopperMoveDeferredNanos());
        assertEquals(2100000L, snapshot.hopperMoveEventPeakNanos());
        assertEquals(3100000L, snapshot.hopperMoveDeferredPeakNanos());
    }
}
