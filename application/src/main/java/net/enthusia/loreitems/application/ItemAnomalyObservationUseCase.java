package net.enthusia.loreitems.application;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.domain.InstanceAnomaly;
import net.enthusia.loreitems.domain.InstanceObservation;
import net.enthusia.loreitems.domain.LocationDescriptor;

public interface ItemAnomalyObservationUseCase {
    int MAX_DETAIL_LENGTH = InstanceAnomaly.MAX_DETAIL_LENGTH;

    CompletionStage<Result> record(Request request);

    enum Kind {
        DUPLICATE_INSTANCE(InstanceAnomaly.Type.DUPLICATE_INSTANCE),
        MALFORMED_STACK(InstanceAnomaly.Type.MALFORMED_STACK);

        private final InstanceAnomaly.Type anomalyType;

        Kind(InstanceAnomaly.Type anomalyType) {
            this.anomalyType = anomalyType;
        }

        public InstanceAnomaly.Type anomalyType() {
            return anomalyType;
        }
    }

    enum Status {
        RECORDED,
        REFRESHED,
        UNKNOWN_INSTANCE,
        IDENTITY_MISMATCH,
        INACTIVE_INSTANCE,
        TERMINAL_INSTANCE,
        STALE,
        SERVICE_UNAVAILABLE
    }

    record Request(
            Kind kind,
            LoreItemIdentity identity,
            LocationDescriptor location,
            String source,
            String detail) {
        public Request {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(location, "location");
            source = requireText(source, "source", InstanceObservation.MAX_SOURCE_LENGTH);
            detail = requireText(detail, "detail", MAX_DETAIL_LENGTH);
            if (location.type() == LocationDescriptor.Type.VOID_DESTROYED) {
                throw new IllegalArgumentException(
                        "Anomaly observations cannot use terminal void locations");
            }
        }
    }

    record Result(Status status, String detail) {
        public Result {
            Objects.requireNonNull(status, "status");
            detail = requireText(detail, "detail", MAX_DETAIL_LENGTH);
        }

        public boolean shouldWarnStaff() {
            return status == Status.RECORDED || status == Status.REFRESHED;
        }

        public static Result of(Status status, String detail) {
            return new Result(status, detail);
        }
    }

    private static String requireText(String value, String name, int maxLength) {
        Objects.requireNonNull(value, name);
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return normalized;
    }
}
