package net.enthusia.loreitems.application;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.domain.InstanceObservation;
import net.enthusia.loreitems.domain.LocationDescriptor;

public interface DisplayItemObservationUseCase {
    int MAX_DETAIL_LENGTH = 4_096;

    CompletionStage<Result> record(Request request);

    enum Presence {
        PRESENT,
        ABSENT
    }

    enum Status {
        RECORDED,
        UNCHANGED,
        STALE,
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
            String source) {
        public Request {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(presence, "presence");
            Objects.requireNonNull(source, "source");
            source = source.strip();
            if (source.isEmpty()
                    || source.length() > InstanceObservation.MAX_SOURCE_LENGTH) {
                throw new IllegalArgumentException("Invalid display observation source");
            }
            if (location.type() != LocationDescriptor.Type.ITEM_FRAME
                    && location.type() != LocationDescriptor.Type.ARMOR_STAND) {
                throw new IllegalArgumentException(
                        "Display observations require an item-frame or armor-stand location");
            }
            if (location.containerPath() == null) {
                throw new IllegalArgumentException(
                        "Display observations require an exact display slot path");
            }
        }
    }

    record Result(Status status, String detail) {
        public Result {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(detail, "detail");
            detail = detail.strip();
            if (detail.isEmpty() || detail.length() > MAX_DETAIL_LENGTH) {
                throw new IllegalArgumentException("Invalid display observation detail");
            }
        }

        public static Result of(Status status, String detail) {
            return new Result(status, detail);
        }
    }
}
