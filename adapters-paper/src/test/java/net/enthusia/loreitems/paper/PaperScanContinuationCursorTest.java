package net.enthusia.loreitems.paper;

import static net.enthusia.loreitems.paper.PaperScanContinuationCursor.Admission;
import static net.enthusia.loreitems.paper.PaperScanContinuationCursor.Cursor;
import static net.enthusia.loreitems.paper.PaperScanContinuationCursor.FairRetries;
import static net.enthusia.loreitems.paper.PaperScanContinuationCursor.Frame;
import static net.enthusia.loreitems.paper.PaperScanContinuationCursor.Outcome;
import static net.enthusia.loreitems.paper.PaperScanContinuationCursor.Scope;
import static net.enthusia.loreitems.paper.PaperScanContinuationCursor.Stamp;
import static net.enthusia.loreitems.paper.PaperScanContinuationCursor.State;
import static net.enthusia.loreitems.paper.PaperScanContinuationCursor.advance;
import static net.enthusia.loreitems.paper.PaperScanContinuationCursor.begin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaperScanContinuationCursorTest {
    private static final UUID WORLD_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WORLD_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String SOURCE = "block-v2:11111111-1111-1111-1111-111111111111:16:64:0";

    private static Stamp stamp(UUID world, long load, long version, long dirty, int roots) {
        return new Stamp(new Scope(world, SOURCE, load, 4, "pair:left+right", roots),
                version, dirty, true);
    }

    private static Frame root(int slot, int width) {
        return new Frame("root", slot, width);
    }

    private static Cursor completeOnePage(Stamp stamp) {
        return advance(begin(stamp), stamp, List.of(), 2, true).cursor();
    }

    @Test
    void sevenHundredOneRootsFinishInThreeBoundedPages() {
        Stamp observed = stamp(WORLD_A, 1, 2, 1, 701);
        Cursor first = begin(observed);
        Outcome one = advance(first, observed, List.of(root(256, 701)), 256, false);
        Outcome two = advance(one.cursor(), observed, List.of(root(512, 701)), 256, false);
        Outcome three = advance(two.cursor(), observed, List.of(), 189, true);
        assertEquals(State.INCOMPLETE, one.state());
        assertEquals(State.INCOMPLETE, two.state());
        assertEquals(State.COMPLETE, three.state());
        assertEquals(701, three.cursor().visits());
        assertEquals(3, three.cursor().pages());
        assertFalse(three.authoritativeEligible());
    }

    @Test
    void slotTwoHundredFiftySevenIsNotLostAtTheBudgetBoundary() {
        Stamp observed = stamp(WORLD_A, 1, 0, 0, 257);
        Outcome first = advance(begin(observed), observed, List.of(root(256, 257)), 256, false);
        assertEquals(root(256, 257), first.cursor().path().get(0));
        Outcome last = advance(first.cursor(), observed, List.of(), 1, true);
        assertEquals(State.COMPLETE, last.state());
        assertEquals(257, last.cursor().visits());
        assertFalse(last.authoritativeEligible());
    }

    @Test
    void nestedShulkerAndBundleOffsetsAreResumableWithoutBukkitReferences() {
        Stamp observed = stamp(WORLD_A, 3, 6, 2, 54);
        Cursor start = begin(observed);
        List<Frame> nested = List.of(root(53, 54), new Frame("shulker", 12, 27),
                new Frame("bundle", 2, 8));
        Outcome first = advance(start, observed, nested, 35, false);
        Outcome second = advance(first.cursor(), observed,
                List.of(root(53, 54), new Frame("shulker", 12, 27),
                        new Frame("bundle", 3, 8)), 1, false);
        assertEquals(State.INCOMPLETE, second.state());
        assertEquals(3, second.cursor().path().size());
        assertEquals(State.COMPLETE,
                advance(second.cursor(), observed, List.of(), 8, true).state());
    }

    @Test
    void differentWorldAndLoadGenerationInvalidateOldPages() {
        Stamp initial = stamp(WORLD_A, 7, 0, 0, 300);
        Cursor continuation = advance(begin(initial), initial, List.of(root(256, 300)),
                256, false).cursor();
        assertEquals(State.INVALIDATED,
                advance(continuation, stamp(WORLD_B, 7, 0, 0, 300),
                        List.of(), 44, true).state());
        assertEquals(State.INVALIDATED,
                advance(continuation, stamp(WORLD_A, 8, 0, 0, 300),
                        List.of(), 44, true).state());
    }

    @Test
    void changingVersionDirtyEpochOrGeometryRejectsStaleCompletion() {
        Stamp initial = stamp(WORLD_A, 7, 10, 4, 300);
        Cursor cursor = begin(initial);
        assertEquals(State.INVALIDATED,
                advance(cursor, stamp(WORLD_A, 7, 11, 4, 300),
                        List.of(), 1, true).state());
        assertEquals(State.INVALIDATED,
                advance(cursor, stamp(WORLD_A, 7, 10, 5, 300),
                        List.of(), 1, true).state());
        Stamp changedPair = new Stamp(new Scope(WORLD_A, SOURCE, 7, 4,
                "pair:rotated", 300), 10, 4, true);
        assertEquals(State.INVALIDATED,
                advance(cursor, changedPair, List.of(), 1, true).state());
    }

    @Test
    void unloadAndNaturalReloadRestartAtSafeOrigin() {
        Stamp initial = stamp(WORLD_A, 7, 10, 4, 300);
        Cursor continuation = advance(begin(initial), initial, List.of(root(256, 300)),
                256, false).cursor();
        Stamp unloaded = new Stamp(initial.scope(), 10, 4, false);
        Outcome missing = advance(continuation, unloaded, List.of(), 44, true);
        assertEquals(State.UNAVAILABLE, missing.state());
        assertNull(missing.cursor());
        Stamp reloaded = stamp(WORLD_A, 8, 0, 0, 300);
        assertEquals(List.of(root(0, 300)), begin(reloaded).path());
        assertFalse(new Outcome(State.COMPLETE, completeOnePage(reloaded))
                .authoritativeEligible());
    }

    @Test
    void terminalReplayAndReusedOldPageCannotProduceAuthority() {
        Stamp observed = stamp(WORLD_A, 1, 0, 0, 3);
        Cursor starting = begin(observed);
        Outcome completed = advance(starting, observed, List.of(), 3, true);
        Outcome replay = advance(completed.cursor(), observed, List.of(), 3, true);
        assertEquals(State.REPLAYED, replay.state());
        assertEquals(completed.cursor(), replay.cursor());
        assertFalse(replay.authoritativeEligible());
        assertFalse(advance(starting, observed, List.of(), 3, true).authoritativeEligible());
    }

    @Test
    void invalidPagesCannotSkipBackwardsOrExceedBudget() {
        Stamp observed = stamp(WORLD_A, 1, 0, 0, 300);
        Cursor cursor = advance(begin(observed), observed,
                List.of(root(200, 300)), 200, false).cursor();
        assertThrows(IllegalArgumentException.class, () -> advance(cursor, observed,
                List.of(root(199, 300)), 1, false));
        assertThrows(IllegalArgumentException.class, () -> advance(cursor, observed,
                List.of(root(201, 300)), 257, false));
        assertThrows(IllegalArgumentException.class, () -> advance(cursor, observed,
                List.of(root(201, 300)), 0, false));
        assertThrows(IllegalArgumentException.class, () -> advance(cursor, observed,
                List.of(root(201, 300)), 1, true));
    }

    @Test
    void cursorAndNestedPositionAreJavaSerializable() throws Exception {
        Stamp observed = stamp(WORLD_A, 1, 0, 0, 54);
        Cursor original = advance(begin(observed), observed,
                List.of(root(42, 54), new Frame("shulker", 7, 27)), 45, false).cursor();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(original);
        }
        assertNotEquals(0, bytes.size());
        assertTrue(java.io.Serializable.class.isAssignableFrom(original.getClass()));
        assertEquals(List.of(root(42, 54), new Frame("shulker", 7, 27)),
                original.path());
    }

    @Test
    void failedAdmissionsRemainFairAndOverflowForcesRediscovery() {
        FairRetries retries = new FairRetries(2);
        Cursor a = begin(stamp(WORLD_A, 1, 0, 0, 257));
        Cursor b = begin(stamp(WORLD_B, 1, 0, 0, 257));
        Cursor c = begin(new Stamp(new Scope(WORLD_A, "other", 1, 4,
                "single", 9), 0, 0, true));
        assertEquals(Admission.STORED, retries.offer(a));
        assertEquals(Admission.STORED, retries.offer(b));
        assertEquals(a, retries.next());
        assertEquals(b, retries.next());
        assertEquals(a, retries.next());
        assertEquals(2, retries.size());
        assertEquals(Admission.REDISCOVERY_REQUIRED, retries.offer(c));
        assertTrue(retries.rediscoveryRequired());
        long token = retries.rediscoveryToken();
        retries.reportFullRediscovery(token, false);
        assertTrue(retries.rediscoveryRequired());
        assertEquals(Admission.REDISCOVERY_REQUIRED, retries.offer(c));
        retries.reportFullRediscovery(token, true);
        assertTrue(retries.rediscoveryRequired(), "New overflow invalidates old sweep proof");
        Cursor doneA = completeOnePage(a.stamp());
        assertEquals(Admission.UPDATED, retries.offer(doneA));
        assertTrue(retries.acknowledge(doneA));
        assertEquals(Admission.STORED, retries.offer(c));
        retries.reportFullRediscovery(retries.rediscoveryToken(), true);
        assertFalse(retries.rediscoveryRequired());
    }

    @Test
    void staleReplayCannotOverwriteNewerDirtyOrLoadGeneration() {
        FairRetries retries = new FairRetries(2);
        Cursor old = begin(stamp(WORLD_A, 2, 5, 1, 300));
        Cursor progressed = advance(old, old.stamp(),
                List.of(root(256, 300)), 256, false).cursor();
        retries.offer(progressed);
        assertEquals(Admission.STALE_IGNORED, retries.offer(old));
        assertEquals(progressed, retries.next());
        Cursor dirty = begin(stamp(WORLD_A, 2, 5, 2, 300));
        assertEquals(Admission.UPDATED, retries.offer(dirty));
        assertEquals(root(0, 300), retries.next().path().get(0));
        assertEquals(Admission.STALE_IGNORED, retries.offer(progressed));
        Cursor reload = begin(stamp(WORLD_A, 3, 0, 0, 300));
        assertEquals(Admission.UPDATED, retries.offer(reload));
        assertEquals(reload, retries.next());
    }

    @Test
    void onlyMatchingTerminalCursorCanBeAcknowledgedOnce() {
        Stamp observed = stamp(WORLD_A, 1, 0, 0, 257);
        FairRetries retries = new FairRetries(1);
        Cursor initial = begin(observed);
        retries.offer(initial);
        Cursor last = advance(initial, observed, List.of(), 10, true).cursor();
        assertFalse(retries.acknowledge(last), "Unrecorded completion is not an ACK");
        assertEquals(Admission.UPDATED, retries.offer(last));
        assertTrue(retries.acknowledge(last));
        assertFalse(retries.acknowledge(last));
        assertEquals(0, retries.size());
    }
}
