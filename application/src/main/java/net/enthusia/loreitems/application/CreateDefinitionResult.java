package net.enthusia.loreitems.application;

import java.util.Objects;
import net.enthusia.loreitems.domain.LoreDefinitionId;

public record CreateDefinitionResult(
        CreateDefinitionStatus status,
        LoreDefinitionId definitionId) {
    public CreateDefinitionResult {
        Objects.requireNonNull(status, "status");
        if ((status == CreateDefinitionStatus.CREATED) != (definitionId != null)) {
            throw new IllegalArgumentException(
                    "Only a successful creation result may contain a definition ID");
        }
    }

    public static CreateDefinitionResult created(LoreDefinitionId definitionId) {
        return new CreateDefinitionResult(
                CreateDefinitionStatus.CREATED,
                Objects.requireNonNull(definitionId, "definitionId"));
    }

    public static CreateDefinitionResult activeKeyExists() {
        return new CreateDefinitionResult(CreateDefinitionStatus.ACTIVE_KEY_EXISTS, null);
    }

    public static CreateDefinitionResult serviceUnavailable() {
        return new CreateDefinitionResult(CreateDefinitionStatus.SERVICE_UNAVAILABLE, null);
    }
}
