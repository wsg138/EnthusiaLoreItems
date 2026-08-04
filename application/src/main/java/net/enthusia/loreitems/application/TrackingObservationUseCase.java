package net.enthusia.loreitems.application;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.domain.InstanceObservation;
import net.enthusia.loreitems.domain.LocationDescriptor;

/** Records durable current or last-confirmed evidence for a tracked physical item. */
public interface TrackingObservationUseCase {
    int MAX_DETAIL_LENGTH = 4_096;

    CompletionStage<Result> record(Request request);

    enum Presence {
        PRESENT,
        LAST_CONFIRMED
    }

    /**
     * Reconciliation evidence is conservative and fences a conflicting live location. An
     * authoritative transition is emitted only by a Paper event that directly represents a move
     * such as a successful drop, pickup, or inventory transfer.
     */
    enum EvidenceMode {
        RECONCILIATION,
        AUTHORITATIVE_TRANSITION
    }

    enum Status {
        RECORDED,
        UNCHANGED,
        STALE,
        CONFLICT_RECORDED,
        UNKNOWN_INSTANCE,
        IDENTITY_MISMATCH,
        INACTIVE_INSTANCE,
        BLOCKED_ANOMALY,
        SERVICE_UNAVAILABLE
    }

    record Request(
            LoreItemIdentity identity,
            LocationDescriptor location,
            Presence presence,
            EvidenceMode mode,
            String source) {
        public Request {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(presence, "presence");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(source, "source");
            source = source.strip();
            if (source.isEmpty() || source.length() > InstanceObservation.MAX_SOURCE_LENGTH) {
                throw new IllegalArgumentException("Invalid tracking observation source");
            }
            if (location.type() == LocationDescriptor.Type.VOID_DESTROYED
                    || location.type() == LocationDescriptor.Type.DUPLICATE_CONFLICT) {
                throw new IllegalArgumentException(
                        "Ordinary tracking observations require a physical location");
            }
            if (presence == Presence.LAST_CONFIRMED
                    && mode == EvidenceMode.AUTHORITATIVE_TRANSITION) {
                throw new IllegalArgumentException(
                        "Last-confirmed evidence cannot be an authoritative transition");
            }
        }
    }

    record Result(Status status, String detail) {
        public Result {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(detail, "detail");
            detail = detail.strip();
            if (detail.isEmpty() || detail.length() > MAX_DETAIL_LENGTH) {
                throw new IllegalArgumentException("Invalid tracking observation detail");
            }
        }

        public static Result of(Status status, String detail) {
            return new Result(status, detail);
        }
    }
}
