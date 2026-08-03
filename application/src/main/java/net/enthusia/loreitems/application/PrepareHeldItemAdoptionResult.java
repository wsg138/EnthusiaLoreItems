package net.enthusia.loreitems.application;

import java.util.Objects;

public record PrepareHeldItemAdoptionResult(
        Status status,
        PreparedHeldItemAdoption preparedAdoption) {
    public PrepareHeldItemAdoptionResult {
        Objects.requireNonNull(status, "status");
        if ((status == Status.PREPARED) != (preparedAdoption != null)) {
            throw new IllegalArgumentException(
                    "Only PREPARED results may contain a prepared adoption");
        }
    }

    public static PrepareHeldItemAdoptionResult prepared(
            PreparedHeldItemAdoption adoption) {
        return new PrepareHeldItemAdoptionResult(
                Status.PREPARED, Objects.requireNonNull(adoption, "adoption"));
    }

    public static PrepareHeldItemAdoptionResult unknownDefinition() {
        return new PrepareHeldItemAdoptionResult(Status.UNKNOWN_DEFINITION, null);
    }

    public static PrepareHeldItemAdoptionResult serviceUnavailable() {
        return new PrepareHeldItemAdoptionResult(Status.SERVICE_UNAVAILABLE, null);
    }

    public enum Status {
        PREPARED,
        UNKNOWN_DEFINITION,
        SERVICE_UNAVAILABLE
    }
}
