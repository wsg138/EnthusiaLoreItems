package net.enthusia.loreitems.application;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record ExternalDeliveryAcceptance(
        ExternalDeliveryOutcome outcome,
        String externalOperationId,
        Optional<UUID> deliveryId,
        String detail) {
    public ExternalDeliveryAcceptance {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(externalOperationId, "externalOperationId");
        deliveryId = Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(detail, "detail");
    }
}
