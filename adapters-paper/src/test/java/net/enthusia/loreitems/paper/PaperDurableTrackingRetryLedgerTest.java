package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import net.enthusia.loreitems.domain.LocationDescriptor;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.junit.jupiter.api.Test;

class PaperDurableTrackingRetryLedgerTest {
    private static final String FIRST_PLAYER = "player:one";
    private static final String ROOT_SLOT = "slot:0";
    private static final String FIRST_SLOT = "slot:1";
    private static final LoreItemIdentity IDENTITY = new LoreItemIdentity(
            new LoreDefinitionId(UUID.fromString("11111111-1111-1111-1111-111111111111")),
            new LoreInstanceId(UUID.fromString("22222222-2222-2222-2222-222222222222")),
            new TemplateRevision(1));

    @Test
    void onlyNewerConfirmedReconciliationClearsFailedHolder() {
        PaperDurableTrackingRetryLedger ledger = new PaperDurableTrackingRetryLedger();
        var request = request(FIRST_PLAYER, FIRST_SLOT,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION);
        var authoritative = request(FIRST_PLAYER, "slot:2",
                TrackingObservationUseCase.EvidenceMode.AUTHORITATIVE_TRANSITION);

        assertFalse(ledger.markUnresolved(request, 12));
        assertFalse(ledger.reconcile(request, 12));
        assertFalse(ledger.reconcile(authoritative, 13));
        assertTrue(ledger.needsReconciliation(authoritative));
        assertTrue(ledger.reconcile(request, 14));
        assertFalse(ledger.needsReconciliation(request));
    }

    @Test
    void locationSlotChangesShareHolderButDifferentPlayersDoNot() {
        PaperDurableTrackingRetryLedger ledger = new PaperDurableTrackingRetryLedger();
        var one = request(FIRST_PLAYER, FIRST_SLOT,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION);
        var two = request("player:two", FIRST_SLOT,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION);
        ledger.markUnresolved(one, 1);

        assertTrue(ledger.needsReconciliation(request(FIRST_PLAYER, "slot:41",
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION)));
        assertFalse(ledger.needsReconciliation(two));
        assertFalse(ledger.reconcile(two, 2));
        assertEquals(1, ledger.snapshot().unresolvedHolders());
    }

    @Test
    void stickyOverflowCannotClaimEvenPreviouslyEnumeratedHolderWasRecovered() {
        PaperDurableTrackingRetryLedger ledger = new PaperDurableTrackingRetryLedger();
        var first = request(FIRST_PLAYER, ROOT_SLOT,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION);
        ledger.markUnresolved(first, 1);
        for (int index = 1; index <= PaperDurableTrackingRetryLedger.MAX_UNRESOLVED; index++) {
            ledger.markUnresolved(request("player:" + index, ROOT_SLOT,
                    TrackingObservationUseCase.EvidenceMode.RECONCILIATION), index + 1L);
        }
        assertTrue(ledger.snapshot().overflow());
        assertFalse(ledger.reconcile(first, 9_999));
        assertTrue(ledger.needsReconciliation(first));
    }

    @Test
    void rejectedAuthoritativeMoveDirtiesPriorSourceAndEveryOtherHolder() {
        PaperDurableTrackingRetryLedger ledger = new PaperDurableTrackingRetryLedger();
        var originalChest = new TrackingObservationUseCase.Request(IDENTITY,
                new LocationDescriptor(LocationDescriptor.Type.BLOCK_CONTAINER,
                        "test:chest", "slot:53"),
                TrackingObservationUseCase.Presence.PRESENT,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION, "ledger-test");
        var attemptedDestination = request(FIRST_PLAYER, FIRST_SLOT,
                TrackingObservationUseCase.EvidenceMode.AUTHORITATIVE_TRANSITION);
        ledger.markUnresolved(attemptedDestination, 2);
        assertTrue(ledger.needsReconciliation(originalChest));
        assertTrue(ledger.needsReconciliation(attemptedDestination));
        assertFalse(ledger.reconcile(originalChest, 3));
        assertFalse(ledger.reconcile(request(FIRST_PLAYER, "slot:2",
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION), 4));
        assertEquals(1, ledger.snapshot().unresolvedHolders());
        assertTrue(ledger.needsReconciliation(originalChest));
    }

    @Test
    void overflowIsStickyAndConservativelyCoversUntrackedHolders() {
        PaperDurableTrackingRetryLedger ledger = new PaperDurableTrackingRetryLedger();
        for (int index = 0; index < PaperDurableTrackingRetryLedger.MAX_UNRESOLVED; index++) {
            assertFalse(ledger.markUnresolved(request("player:" + index, ROOT_SLOT,
                    TrackingObservationUseCase.EvidenceMode.RECONCILIATION), index));
        }
        var overflowRequest = request("player:overflow", ROOT_SLOT,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION);
        assertTrue(ledger.markUnresolved(overflowRequest, 4_097));
        assertFalse(ledger.markUnresolved(overflowRequest, 4_098));
        assertEquals(PaperDurableTrackingRetryLedger.MAX_UNRESOLVED,
                ledger.snapshot().unresolvedHolders());
        assertEquals(2, ledger.snapshot().overflowedKeys());
        assertTrue(ledger.snapshot().overflow());
        assertTrue(ledger.needsReconciliation(overflowRequest));
        assertFalse(ledger.reconcile(overflowRequest, 4_099));
        assertTrue(ledger.snapshot().overflow());
    }

    private static TrackingObservationUseCase.Request request(
            String holder, String slot, TrackingObservationUseCase.EvidenceMode mode) {
        return new TrackingObservationUseCase.Request(IDENTITY,
                new LocationDescriptor(LocationDescriptor.Type.PLAYER_INVENTORY, holder, slot),
                TrackingObservationUseCase.Presence.PRESENT, mode, "durable-ledger-test");
    }
}
