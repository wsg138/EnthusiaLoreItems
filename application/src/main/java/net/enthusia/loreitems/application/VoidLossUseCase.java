package net.enthusia.loreitems.application;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.domain.LocationDescriptor;

public interface VoidLossUseCase {
    CompletionStage<PrepareResult> prepare(Request request);

    CompletionStage<Boolean> complete(PreparedVoidLoss loss);

    CompletionStage<Boolean> abort(PreparedVoidLoss loss, String reason);

    CompletionStage<Boolean> requireReview(PreparedVoidLoss loss, String reason);

    record Request(LoreItemIdentity identity, UUID entityId, String locationKey) {
        public Request {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(entityId, "entityId");
            Objects.requireNonNull(locationKey, "locationKey");
            locationKey = locationKey.strip();
            if (locationKey.isEmpty()
                    || locationKey.length() > LocationDescriptor.MAX_LOCATION_KEY_LENGTH) {
                throw new IllegalArgumentException("Invalid void location key");
            }
        }
    }

    enum PrepareStatus {
        PREPARED,
        ALREADY_TERMINAL,
        UNKNOWN_INSTANCE,
        IDENTITY_MISMATCH,
        REVIEW_REQUIRED,
        SERVICE_UNAVAILABLE
    }

    record PrepareResult(
            PrepareStatus status,
            PreparedVoidLoss prepared,
            String detail) {
        public PrepareResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(detail, "detail");
            detail = detail.strip();
            if (detail.isEmpty()) {
                throw new IllegalArgumentException("detail must not be blank");
            }
            if ((status == PrepareStatus.PREPARED) != (prepared != null)) {
                throw new IllegalArgumentException(
                        "Only PREPARED results may contain prepared work");
            }
        }

        public static PrepareResult prepared(PreparedVoidLoss loss) {
            return new PrepareResult(PrepareStatus.PREPARED, loss, "Void loss prepared.");
        }

        public static PrepareResult of(PrepareStatus status, String detail) {
            if (status == PrepareStatus.PREPARED) {
                throw new IllegalArgumentException("Use prepared() for PREPARED results");
            }
            return new PrepareResult(status, null, detail);
        }
    }
}
