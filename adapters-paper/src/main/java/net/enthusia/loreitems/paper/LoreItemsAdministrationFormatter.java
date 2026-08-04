package net.enthusia.loreitems.paper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.enthusia.loreitems.application.AuditEventRecord;
import net.enthusia.loreitems.application.DirectDeliveryRecord;
import net.enthusia.loreitems.application.LoreItemsAdministrationUseCase;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PendingMutationRecord;
import net.enthusia.loreitems.domain.InstanceAnomaly;
import net.enthusia.loreitems.domain.InstanceCurrentState;
import net.enthusia.loreitems.domain.InstanceObservation;
import net.enthusia.loreitems.domain.LocationDescriptor;
import net.enthusia.loreitems.domain.LoreInstanceId;

final class LoreItemsAdministrationFormatter {
    private static final int MAX_SUMMARY_LENGTH = 180;
    private static final int FIRST_PAGE_NUMBER = 1;

    private LoreItemsAdministrationFormatter() {}

    static List<String> anomalyLines(Page<InstanceAnomaly> page) {
        List<String> lines = new ArrayList<>();
        lines.add("Active lore-item anomalies — page " + pageNumber(page));
        if (page.items().isEmpty()) {
            lines.add("No active anomalies were found.");
            return lines;
        }
        for (InstanceAnomaly anomaly : page.items()) {
            String instance = anomaly.instanceId() == null
                    ? "unknown-instance"
                    : anomaly.instanceId().value().toString();
            lines.add(anomaly.type().name() + " " + anomaly.status().name()
                    + " instance=" + instance
                    + " anomaly=" + anomaly.anomalyId()
                    + " last=" + Instant.ofEpochMilli(anomaly.lastSeenAtEpochMillis())
                    + " detail=" + summarize(anomaly.detail()));
        }
        if (page.hasMore()) {
            lines.add("More results are available on page "
                    + (pageNumber(page) + FIRST_PAGE_NUMBER) + '.');
        }
        return lines;
    }

    static List<String> auditLines(LoreInstanceId instanceId, AuditView view) {
        List<String> lines = new ArrayList<>();
        lines.add("Lore-item evidence for " + instanceId.value());
        appendCurrentState(lines, view.currentState());
        appendObservationLines(lines, view.observations());
        appendAnomalyLines(lines, view.anomalies());
        appendAuditLines(lines, view.audit());
        if (hasNoEvidence(view)) {
            lines.add("No current state, location, anomaly, or audit evidence was found.");
        } else if (hasMoreEvidence(view)) {
            lines.add("More evidence is available on the next page.");
        }
        return lines;
    }

    static List<String> recoveryLines(LoreItemsAdministrationUseCase.RecoveryPage page) {
        List<String> lines = new ArrayList<>();
        lines.add("Nonterminal lore-item recovery work — page "
                + pageNumber(page.deliveries()));
        if (page.deliveries().items().isEmpty() && page.mutations().items().isEmpty()) {
            lines.add("No nonterminal delivery or mutation records were found.");
            return lines;
        }
        for (DirectDeliveryRecord delivery : page.deliveries().items()) {
            lines.add("DELIVERY " + delivery.state().name()
                    + " delivery=" + delivery.deliveryId()
                    + " instance=" + delivery.instanceId().value()
                    + " player=" + delivery.playerId()
                    + " attempts=" + delivery.attemptCount());
        }
        for (PendingMutationRecord mutation : page.mutations().items()) {
            lines.add("MUTATION " + mutation.state().name()
                    + " type=" + mutation.mutationType()
                    + " mutation=" + mutation.mutationId()
                    + " instance=" + nullableInstance(mutation)
                    + " attempts=" + mutation.attemptCount());
        }
        if (page.hasMore()) {
            lines.add("More recovery records are available on the next page.");
        }
        return lines;
    }

    private static void appendObservationLines(
            List<String> lines,
            Page<InstanceObservation> observations) {
        for (InstanceObservation observation : observations.items()) {
            lines.add("OBSERVATION " + observation.observationId()
                    + " " + observation.confidence().name()
                    + " at=" + formatLocation(observation.location())
                    + " source=" + observation.source()
                    + " observed=" + Instant.ofEpochMilli(observation.observedAtEpochMillis()));
        }
    }

    private static void appendAnomalyLines(
            List<String> lines,
            Page<InstanceAnomaly> anomalies) {
        for (InstanceAnomaly anomaly : anomalies.items()) {
            lines.add("ANOMALY " + anomaly.type().name() + " " + anomaly.status().name()
                    + " id=" + anomaly.anomalyId()
                    + " detail=" + summarize(anomaly.detail()));
        }
    }

    private static void appendAuditLines(List<String> lines, Page<AuditEventRecord> audit) {
        for (AuditEventRecord event : audit.items()) {
            lines.add("AUDIT " + Instant.ofEpochMilli(event.occurredAtEpochMillis())
                    + " " + event.eventType()
                    + " actor=" + event.actorType() + ':' + safeActor(event.actorId())
                    + " detail=" + summarize(event.detailJson()));
        }
    }

    private static void appendCurrentState(
            List<String> lines,
            Optional<InstanceCurrentState> currentState) {
        currentState.ifPresent(state -> {
            String location = state.location() == null
                    ? "none"
                    : formatLocation(state.location());
            lines.add("STATE " + state.state().name()
                    + " revision=" + state.stateRevision()
                    + " at=" + location
                    + " updated=" + Instant.ofEpochMilli(state.updatedAtEpochMillis()));
        });
    }

    private static boolean hasNoEvidence(AuditView view) {
        return view.currentState().isEmpty()
                && view.observations().items().isEmpty()
                && view.anomalies().items().isEmpty()
                && view.audit().items().isEmpty();
    }

    private static boolean hasMoreEvidence(AuditView view) {
        return view.observations().hasMore()
                || view.audit().hasMore()
                || view.anomalies().hasMore();
    }

    private static String formatLocation(LocationDescriptor location) {
        String path = location.containerPath() == null
                ? ""
                : ":" + location.containerPath();
        return summarize(location.type().name() + ':' + location.locationKey() + path);
    }

    private static int pageNumber(Page<?> page) {
        return page.offset() / page.limit() + FIRST_PAGE_NUMBER;
    }

    private static String safeActor(String actorId) {
        return actorId == null ? "system" : actorId;
    }

    private static String nullableInstance(PendingMutationRecord mutation) {
        return mutation.instanceId() == null
                ? "none"
                : mutation.instanceId().value().toString();
    }

    private static String summarize(String value) {
        String flattened = value.replace('\n', ' ').replace('\r', ' ').strip();
        return flattened.length() <= MAX_SUMMARY_LENGTH
                ? flattened
                : flattened.substring(0, MAX_SUMMARY_LENGTH - 3) + "...";
    }

    record AuditView(
            Optional<InstanceCurrentState> currentState,
            Page<InstanceObservation> observations,
            Page<InstanceAnomaly> anomalies,
            Page<AuditEventRecord> audit) {}
}
