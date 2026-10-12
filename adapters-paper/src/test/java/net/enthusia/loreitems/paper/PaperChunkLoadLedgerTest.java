package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaperChunkLoadLedgerTest {
    private static final UUID FIRST = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SECOND = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final int DIRECT_ADMISSION_CAPACITY = 512;

    private static PaperChunkLoadLedger.Pending startPending(
            PaperChunkLoadLedger ledger, PaperTrackingScanRequest.ChunkReference key) {
        return new PaperChunkLoadLedger.Pending(key, ledger.observeLoad(key));
    }

    @Test
    void thousandDuplicateLoadsOneGenerationButReloadReceivesAnother() {
        PaperChunkLoadLedger ledger = new PaperChunkLoadLedger();
        var key = PaperChunkLoadLedger.key(FIRST, 3, -4);
        long generation = ledger.observeLoad(key);
        assertTrue(generation > 0);
        for (int i = 0; i < 1_000; i++) {
            assertEquals(0, ledger.observeLoad(key));
        }
        var pending = new PaperChunkLoadLedger.Pending(key, generation);
        assertTrue(ledger.queued(pending));
        assertTrue(ledger.begin(pending));
        ledger.complete(pending, true, true, 30);
        assertEquals(0, ledger.observeLoad(key));
        assertEquals(1, ledger.loadedCount());
        ledger.unload(key);
        long reload = ledger.observeLoad(key);
        assertNotEquals(generation, reload);
        assertFalse(ledger.begin(pending), "Stale loaded generation must not scan a new chunk");
    }

    @Test
    void allOverflowedLoadedChunksEventuallyObtainTheirOwnScanToken() {
        PaperChunkLoadLedger ledger = new PaperChunkLoadLedger();
        Set<PaperTrackingScanRequest.ChunkReference> scanned = new HashSet<>();
        for (int i = 0; i < 1_200; i++) {
            var key = PaperChunkLoadLedger.key(FIRST, i, 0);
            var pending = startPending(ledger, key);
            if (i < DIRECT_ADMISSION_CAPACITY) {
                assertTrue(ledger.queued(pending));
                assertTrue(ledger.begin(pending));
                scanned.add(key);
                ledger.complete(pending, true, true, 20);
            } else {
                ledger.markDirty(pending); // The bounded queue refused admission.
            }
        }
        assertEquals(688, ledger.dirtyCount());
        PaperChunkLoadLedger.Pending recovered = ledger.nextDirty(20);
        while (recovered != null) {
            assertTrue(ledger.queued(recovered));
            assertTrue(ledger.begin(recovered));
            scanned.add(recovered.key());
            ledger.complete(recovered, true, true, 21);
            recovered = ledger.nextDirty(20);
        }
        assertEquals(1_200, scanned.size());
        assertEquals(0, ledger.dirtyCount());
        assertEquals(1_200, ledger.loadedCount());
    }

    @Test
    void unloadInvalidatesPendingScanWithoutCrossWorldCollision() {
        PaperChunkLoadLedger ledger = new PaperChunkLoadLedger();
        var first = PaperChunkLoadLedger.key(FIRST, 5, 5);
        var second = PaperChunkLoadLedger.key(SECOND, 5, 5);
        var obsolete = new PaperChunkLoadLedger.Pending(first, ledger.observeLoad(first));
        var other = new PaperChunkLoadLedger.Pending(second, ledger.observeLoad(second));
        ledger.markDirty(obsolete);
        ledger.markDirty(other);
        ledger.unload(first);
        assertFalse(ledger.currentGeneration(obsolete));
        assertEquals(other, ledger.nextDirty(1));
        assertNull(ledger.nextDirty(1));
        assertEquals(1, ledger.loadedCount());
    }

    @Test
    void trackedVerificationNeverRunsBeforeSixtySeconds() {
        PaperChunkLoadLedger ledger = new PaperChunkLoadLedger();
        var key = PaperChunkLoadLedger.key(FIRST, 0, 0);
        var pending = new PaperChunkLoadLedger.Pending(key, ledger.observeLoad(key));
        assertTrue(ledger.queued(pending));
        assertTrue(ledger.begin(pending));
        ledger.complete(pending, true, true, 7);
        ledger.observeTracked(key);
        assertNull(ledger.nextTrackedDue(1_206, 1_200));
        assertEquals(pending, ledger.nextTrackedDue(1_207, 1_200));
        assertNull(ledger.nextTrackedDue(1_208, 1_200));
        ledger.unload(key);
        assertNull(ledger.nextTrackedDue(2_500, 1_200));
    }

    @Test
    void partialEvidenceCannotClaimCompletionAndIsThrottled() {
        PaperChunkLoadLedger ledger = new PaperChunkLoadLedger();
        var key = PaperChunkLoadLedger.key(FIRST, -2, 9);
        var pending = new PaperChunkLoadLedger.Pending(key, ledger.observeLoad(key));
        ledger.queued(pending);
        ledger.begin(pending);
        ledger.complete(pending, false, true, 10);
        assertEquals(1, ledger.dirtyCount());
        assertNull(ledger.nextDirty(1_209));
        assertEquals(pending, ledger.nextDirty(1_210));
    }
}
