package net.enthusia.loreitems.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import net.enthusia.loreitems.domain.InstanceObservation;
import net.enthusia.loreitems.domain.LocationDescriptor;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.junit.jupiter.api.Test;

class TrackingObservationUseCaseTest {
    private static final LoreItemIdentity IDENTITY = new LoreItemIdentity(
            new LoreDefinitionId(UUID.fromString("11111111-1111-1111-1111-111111111111")),
            new LoreInstanceId(UUID.fromString("22222222-2222-2222-2222-222222222222")),
            new TemplateRevision(1));

    @Test
    void acceptsPhysicalPresentEvidenceAndNormalizesSource() {
        TrackingObservationUseCase.Request request = new TrackingObservationUseCase.Request(
                IDENTITY,
                new LocationDescriptor(
                        LocationDescriptor.Type.PLAYER_INVENTORY,
                        "player:33333333-3333-3333-3333-333333333333",
                        "slot:4"),
                TrackingObservationUseCase.Presence.PRESENT,
                TrackingObservationUseCase.EvidenceMode.AUTHORITATIVE_TRANSITION,
                "  inventory-slot-change  ");

        assertEquals(TrackingObservationUseCase.Presence.PRESENT, request.presence());
        assertEquals("inventory-slot-change", request.source());
    }

    @Test
    void rejectsSyntheticAndAuthoritativeLastConfirmedEvidence() {
        assertThrows(IllegalArgumentException.class, () -> requestAt(
                LocationDescriptor.Type.DUPLICATE_CONFLICT,
                TrackingObservationUseCase.Presence.PRESENT,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                "scan"));
        assertThrows(IllegalArgumentException.class, () -> requestAt(
                LocationDescriptor.Type.VOID_DESTROYED,
                TrackingObservationUseCase.Presence.PRESENT,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                "scan"));
        assertThrows(IllegalArgumentException.class, () -> requestAt(
                LocationDescriptor.Type.DROPPED_ITEM,
                TrackingObservationUseCase.Presence.LAST_CONFIRMED,
                TrackingObservationUseCase.EvidenceMode.AUTHORITATIVE_TRANSITION,
                "chunk-unload"));
    }

    @Test
    void rejectsBlankAndOversizedSources() {
        assertThrows(IllegalArgumentException.class, () -> requestAt(
                LocationDescriptor.Type.PLAYER_INVENTORY,
                TrackingObservationUseCase.Presence.PRESENT,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                "   "));
        assertThrows(IllegalArgumentException.class, () -> requestAt(
                LocationDescriptor.Type.PLAYER_INVENTORY,
                TrackingObservationUseCase.Presence.PRESENT,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                "x".repeat(InstanceObservation.MAX_SOURCE_LENGTH + 1)));
    }

    private static TrackingObservationUseCase.Request requestAt(
            LocationDescriptor.Type type,
            TrackingObservationUseCase.Presence presence,
            TrackingObservationUseCase.EvidenceMode mode,
            String source) {
        return new TrackingObservationUseCase.Request(
                IDENTITY,
                new LocationDescriptor(
                        type,
                        type == LocationDescriptor.Type.DROPPED_ITEM
                                ? "minecraft:overworld:entity:44444444-4444-4444-4444-444444444444"
                                : "location:key",
                        type == LocationDescriptor.Type.DUPLICATE_CONFLICT ? "copies" : null),
                presence,
                mode,
                source);
    }
}
