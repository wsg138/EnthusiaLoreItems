package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure lifecycle contracts; MockBukkit does not model an authentic split double chest. */
class PaperCrossChunkLifecycleIndexTest {
    private static final UUID FIRST = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SECOND = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final long MIN_INTERVAL = 1_200L;

    private static PaperLoadedTrackedContainerIndex.BlockKey block(UUID world, int x, int z) {
        return new PaperLoadedTrackedContainerIndex.BlockKey(world, x, 64, z);
    }

    private static PaperLoadedTrackedContainerIndex.ContainerKey pair(UUID world, int x) {
        return PaperLoadedTrackedContainerIndex.ContainerKey.paired(
                block(world, x, 0), block(world, x + 1, 0));
    }

    @Test
    void twoHalvesAcrossPositiveAndNegativeChunkBordersUseOneCanonicalIdentity() {
        for (int x : new int[] {-1, 15}) {
            var a = block(FIRST, x, 0);
            var b = block(FIRST, x + 1, 0);
            var fromA = PaperLoadedTrackedContainerIndex.ContainerKey.paired(a, b);
            var fromB = PaperLoadedTrackedContainerIndex.ContainerKey.paired(b, a);
            assertEquals(fromA, fromB);
            assertEquals(2, fromA.chunks().size());
        }
    }

    @Test
    void eitherHalfUnloadsAndNeitherHalfAloneMakesOldDoubleAccessible() {
        var index = new PaperLoadedTrackedContainerIndex();
        var container = pair(FIRST, 15);
        var firstChunk = block(FIRST, 15, 0).chunk();
        var secondChunk = block(FIRST, 16, 0).chunk();
        Set<PaperTrackingScanRequest.ChunkReference> loaded = new HashSet<>(container.chunks());
        index.observed(container, true, true, 10);
        index.chunkUnloaded(firstChunk);
        loaded.remove(firstChunk);
        index.chunkLoaded(secondChunk, loaded::contains);
        assertNull(index.nextWork(20, MIN_INTERVAL, loaded::contains));
        assertEquals(1, index.unresolvedCount());
        loaded.add(firstChunk);
        index.chunkLoaded(firstChunk, loaded::contains);
        assertEquals(container, index.nextWork(21, MIN_INTERVAL, loaded::contains));
        assertNull(index.nextWork(21, MIN_INTERVAL, loaded::contains));
        index.observed(container, true, true, 21);
        index.chunkUnloaded(secondChunk);
        loaded.remove(secondChunk);
        assertNull(index.nextWork(1_300, MIN_INTERVAL, loaded::contains));
        loaded.add(secondChunk);
        index.chunkLoaded(secondChunk, loaded::contains);
        assertEquals(container, index.nextWork(1_301, MIN_INTERVAL, loaded::contains));
    }

    @Test
    void thousandIdenticalNaturalLoadsNeverAddDuplicateRescanWork() {
        var index = new PaperLoadedTrackedContainerIndex();
        var container = pair(FIRST, 15);
        var first = container.first().chunk();
        var second = container.second().chunk();
        index.observed(container, true, true, 10);
        index.chunkUnloaded(first);
        for (int i = 0; i < 1_000; i++) {
            index.chunkLoaded(first, ignored -> true);
        }
        assertEquals(1, index.pendingCount());
        assertEquals(1, index.peakPending());
        assertEquals(container, index.nextWork(11, MIN_INTERVAL, ignored -> true));
        assertNull(index.nextWork(11, MIN_INTERVAL, ignored -> true));
        assertEquals(0, index.pendingCount());
        assertTrue(index.containsBlock(container.first()));
        assertTrue(index.containsBlock(container.second()));
    }

    @Test
    void trackedDoubleGetsOnePeriodicalItemAndNeverRetriesBeforeSixtySeconds() {
        var index = new PaperLoadedTrackedContainerIndex();
        var container = pair(FIRST, 15);
        index.observed(container, true, true, 7);
        assertNull(index.nextWork(1_206, MIN_INTERVAL, ignored -> true));
        assertEquals(container, index.nextWork(1_207, MIN_INTERVAL, ignored -> true));
        assertNull(index.nextWork(1_208, MIN_INTERVAL, ignored -> true));
        assertNull(index.nextWork(2_406, MIN_INTERVAL, ignored -> true));
        assertEquals(container, index.nextWork(2_407, MIN_INTERVAL, ignored -> true));
        index.observed(container, false, true, 2_407);
        assertEquals(0, index.size());
        assertNull(index.nextWork(5_000, MIN_INTERVAL, ignored -> true));
    }

    @Test
    void splitInvalidatesBothHalvesButPreservesNeighborForFollowup() {
        var index = new PaperLoadedTrackedContainerIndex();
        var container = pair(FIRST, -1);
        index.observed(container, true, true, 1);
        var survivors = index.blockChanged(container.first());
        assertEquals(Set.of(container.second()), survivors);
        assertEquals(0, index.size());
        var single = PaperLoadedTrackedContainerIndex.ContainerKey.single(container.second());
        index.observed(single, true, true, 3);
        assertEquals(1, index.size());
        assertFalse(index.containsBlock(container.first()));
        assertTrue(index.containsBlock(container.second()));
    }

    @Test
    void twoWorldsAtIdenticalCoordinatesNeverAlias() {
        var index = new PaperLoadedTrackedContainerIndex();
        var first = pair(FIRST, 15);
        var second = pair(SECOND, 15);
        index.observed(first, true, true, 10);
        index.observed(second, true, true, 10);
        assertEquals(2, index.size());
        index.chunkUnloaded(first.first().chunk());
        assertEquals(1, index.unresolvedCount());
        assertTrue(index.containsBlock(second.first()));
    }

    @Test
    void twelveHundredDistinctKnownContainersRemainScheduledWithoutOverflowLoss() {
        var index = new PaperLoadedTrackedContainerIndex();
        var pending = new HashSet<PaperLoadedTrackedContainerIndex.ContainerKey>();
        for (int i = 0; i < 1_200; i++) {
            var key = PaperLoadedTrackedContainerIndex.ContainerKey.single(
                    block(FIRST, i * 2, i * 3));
            index.observed(key, true, true, 10);
            pending.add(key);
        }
        assertEquals(1_200, index.size());
        for (int i = 0; i < 1_200; i++) {
            var next = index.nextWork(1_210 + i, MIN_INTERVAL, ignored -> true);
            assertTrue(pending.remove(next), "All indexed work must be visited exactly once");
            index.observed(next, true, true, 1_210 + i);
        }
        assertTrue(pending.isEmpty());
    }
}
