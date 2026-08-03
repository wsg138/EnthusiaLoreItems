package net.enthusia.loreitems.application;

import java.util.Objects;
import java.util.UUID;
import net.enthusia.loreitems.domain.DefinitionKey;

public record ExternalDeliveryCommand(
        DefinitionKey definitionKey,
        UUID playerId,
        String externalOperationId) {
    public static final int MAX_OPERATION_ID_LENGTH = 160;

    public ExternalDeliveryCommand {
        Objects.requireNonNull(definitionKey, "definitionKey");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(externalOperationId, "externalOperationId");
        externalOperationId = externalOperationId.strip();
        if (externalOperationId.isEmpty()
                || externalOperationId.length() > MAX_OPERATION_ID_LENGTH) {
            throw new IllegalArgumentException("Invalid external operation id");
        }
    }
}
