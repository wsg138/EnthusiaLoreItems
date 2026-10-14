package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import net.enthusia.loreitems.domain.LocationDescriptor;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.junit.jupiter.api.Test;

class PaperChunkLastConfirmedLifecycleTest {
    private static final UUID WORLD = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final LoreItemIdentity ITEM = new LoreItemIdentity(
            new LoreDefinitionId(WORLD),
            new LoreInstanceId(UUID.fromString("22222222-2222-2222-2222-222222222222")),
            new TemplateRevision(1));
    private static final String PAIR_LOCATION_KEY = "double-chest:" + WORLD + ":15:64:0|16:64:0:left=0";
    private static final String SLOT_FIRST = "slot:0";
    private static final String SLOT_LAST = "slot:53";

    private static PaperLoadedTrackedContainerIndex.ContainerKey pair() {
        return PaperLoadedTrackedContainerIndex.ContainerKey.paired(
                new PaperLoadedTrackedContainerIndex.BlockKey(WORLD, 15, 64, 0),
                new PaperLoadedTrackedContainerIndex.BlockKey(WORLD, 16, 64, 0));
    }

    private static TrackingObservationUseCase.Request present(String slot) {
        return new TrackingObservationUseCase.Request(ITEM,
                new LocationDescriptor(LocationDescriptor.Type.BLOCK_CONTAINER, PAIR_LOCATION_KEY, slot),
                TrackingObservationUseCase.Presence.PRESENT,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION, "recheck");
    }

    private static PaperTrackingAcknowledgement ack(TrackingObservationUseCase.Request req,
            CompletableFuture<PaperTrackingAcknowledgement.Settlement> settlement) {
        return PaperTrackingAcknowledgement.issued(true, settlement, req, 1, new Object());
    }

    private static PaperBoundedInventoryContinuation.Page page(String... slots) {
        List<LocationDescriptor> locations = new ArrayList<>();
        for (String slot : slots) locations.add(present(slot).location());
        return new PaperBoundedInventoryContinuation.Page(
                Map.of(ITEM, locations), PaperBoundedInventoryContinuation.State.COMPLETE,
                null, 54, true);
    }

    @Test
    void lastConfirmedWaitsForDurableAckAndPreservesRightHalfSlot53() {
        var index = new PaperLoadedTrackedContainerIndex();
        var presentAck = new CompletableFuture<PaperTrackingAcknowledgement.Settlement>();
        var transitionAck = new CompletableFuture<PaperTrackingAcknowledgement.Settlement>();
        index.observedPage(pair(), page(SLOT_LAST),
                List.of(ack(present(SLOT_LAST), presentAck)), 10);
        index.chunkUnloaded(pair().second().chunk());
        var requests = new ArrayList<TrackingObservationUseCase.Request>();
        java.util.function.Function<TrackingObservationUseCase.Request,
                PaperTrackingAcknowledgement> sink = req -> {
                    requests.add(req);
                    return ack(req, transitionAck);
                };
        assertEquals(0, index.drainUnloaded(1, sink));
        assertEquals(1, index.pendingUnloadCount());
        presentAck.complete(PaperTrackingAcknowledgement.Settlement.CONFIRMED);
        assertEquals(1, index.drainUnloaded(1, sink));
        assertEquals(SLOT_LAST, requests.getFirst().location().containerPath());
        assertEquals(PAIR_LOCATION_KEY, requests.getFirst().location().locationKey());
        assertEquals(TrackingObservationUseCase.Presence.LAST_CONFIRMED,
                requests.getFirst().presence());
        assertEquals(0, index.drainUnloaded(1, sink));
        transitionAck.complete(PaperTrackingAcknowledgement.Settlement.CONFIRMED);
        assertEquals(0, index.drainUnloaded(1, sink));
        assertEquals(1, requests.size());
        assertEquals(0, index.pendingUnloadCount());
    }

    @Test
    void failureCannotForgeLastConfirmedAndPreservesUncertainty() {
        var index = new PaperLoadedTrackedContainerIndex();
        var failure = CompletableFuture.completedFuture(
                PaperTrackingAcknowledgement.Settlement.FAILED);
        index.observedPage(pair(), page(SLOT_FIRST), List.of(ack(present(SLOT_FIRST), failure)), 1);
        index.chunkUnloaded(pair().first().chunk());
        assertEquals(0, index.drainUnloaded(3, req -> {
            throw new AssertionError("cannot transition failed evidence");
        }));
        assertEquals(1, index.failedTransitions());
        assertEquals(1, index.unresolvedCount());
    }

    @Test
    void exceptionalPresentAcknowledgementAlsoBlocksUnloadTransition() {
        var index = new PaperLoadedTrackedContainerIndex();
        var crashed = new CompletableFuture<PaperTrackingAcknowledgement.Settlement>();
        crashed.completeExceptionally(new IllegalStateException("SQLITE_BUSY"));
        index.observedPage(pair(), page(SLOT_LAST),
                List.of(ack(present(SLOT_LAST), crashed)), 1);
        index.chunkUnloaded(pair().first().chunk());
        assertEquals(0, index.drainUnloaded(1, req -> {
            throw new AssertionError("cannot transition exceptional persistence");
        }));
        assertEquals(1, index.failedTransitions());
    }

    @Test
    void twoDistinctCopiesRetainBothExactHalfSlots() {
        var index = new PaperLoadedTrackedContainerIndex();
        var settled = CompletableFuture.completedFuture(
                PaperTrackingAcknowledgement.Settlement.CONFIRMED);
        index.observedPage(pair(), page(SLOT_FIRST, SLOT_LAST),
                List.of(ack(present(SLOT_FIRST), settled), ack(present(SLOT_LAST), settled)), 1);
        index.chunkUnloaded(pair().second().chunk());
        var slots = new ArrayList<String>();
        for (int i = 0; i < 5; i++) index.drainUnloaded(1, req -> {
            slots.add(req.location().containerPath());
            return ack(req, settled);
        });
        assertEquals(2, slots.size());
        assertTrue(slots.containsAll(List.of(SLOT_FIRST, SLOT_LAST)));
        assertEquals(0, index.pendingUnloadCount());
    }

    @Test
    void emptyRecheckCannotEraseOldDurableEvidenceWithoutAbsenceCas() {
        var index = new PaperLoadedTrackedContainerIndex();
        var settled = CompletableFuture.completedFuture(
                PaperTrackingAcknowledgement.Settlement.CONFIRMED);
        index.observedPage(pair(), page(SLOT_LAST),
                List.of(ack(present(SLOT_LAST), settled)), 1);
        var empty = new PaperBoundedInventoryContinuation.Page(
                Map.of(), PaperBoundedInventoryContinuation.State.COMPLETE, null, 54, true);
        index.observedPage(pair(), empty, List.of(), 2);
        assertEquals(1, index.size());
        assertEquals(1, index.unresolvedCount());
        index.chunkUnloaded(pair().first().chunk());
        var seen = new ArrayList<String>();
        assertEquals(1, index.drainUnloaded(1, req -> {
            seen.add(req.location().containerPath());
            return ack(req, settled);
        }));
        assertEquals(List.of(SLOT_LAST), seen);
    }

    @Test
    void incompletePageRetainsCursorUntilNextTrackedWorkAndInvalidatesOnMutation() {
        var index = new PaperLoadedTrackedContainerIndex();
        var cursor = new PaperBoundedInventoryContinuation.Cursor(
                "BLOCK_CONTAINER:" + PAIR_LOCATION_KEY, 0, PAIR_LOCATION_KEY, 300,
                List.of(new PaperBoundedInventoryContinuation.Frame("slot", 256, 300)));
        var partial = new PaperBoundedInventoryContinuation.Page(
                Map.of(), PaperBoundedInventoryContinuation.State.PARTIAL, cursor, 256, false);
        index.observedPage(pair(), partial, List.of(), 10);
        assertEquals(cursor, index.cursor(pair()));
        assertEquals(pair(), index.nextWork(30, 1_200, ignored -> true));
        index.markDirty(pair().second());
        assertNull(index.cursor(pair()));
        assertEquals(1, index.unresolvedCount());
        assertEquals(pair(), index.nextWork(31, 1_200, ignored -> true));
    }
}
