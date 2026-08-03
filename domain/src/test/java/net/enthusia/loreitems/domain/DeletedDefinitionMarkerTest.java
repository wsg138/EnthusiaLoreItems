package net.enthusia.loreitems.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeletedDefinitionMarkerTest {
    @Test
    void retainsMinimalDeletedDefinitionIdentity() {
        LoreDefinitionId definitionId = new LoreDefinitionId(UUID.randomUUID());
        DefinitionKey lookupKey = new DefinitionKey("historic_item");

        DeletedDefinitionMarker marker =
                new DeletedDefinitionMarker(definitionId, lookupKey, 2_000L);

        assertEquals(definitionId, marker.definitionId());
        assertEquals(lookupKey, marker.lookupKey());
        assertEquals(2_000L, marker.deletedAtEpochMillis());
    }

    @Test
    void rejectsNegativeDeletionTime() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DeletedDefinitionMarker(
                        new LoreDefinitionId(UUID.randomUUID()),
                        new DefinitionKey("historic_item"),
                        -1L));
    }
}
