package net.enthusia.loreitems.paper;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Main-thread-only index of verified or unresolved tracked physical block inventories.
 * An unloaded half retains identity/evidence metadata, but is never considered accessible.
 */
final class PaperLoadedTrackedContainerIndex {
    record BlockKey(UUID worldId, int x, int y, int z) {
        BlockKey {
            Objects.requireNonNull(worldId, "worldId");
        }

        PaperTrackingScanRequest.ChunkReference chunk() {
            return PaperChunkLoadLedger.key(worldId, x >> 4, z >> 4);
        }
    }

    record ContainerKey(BlockKey first, BlockKey second) {
        ContainerKey {
            Objects.requireNonNull(first, "first");
            if (second != null && !first.worldId().equals(second.worldId())) {
                throw new IllegalArgumentException("Container halves must share a world");
            }
        }

        static ContainerKey single(BlockKey block) {
            return new ContainerKey(block, null);
        }

        static ContainerKey paired(BlockKey left, BlockKey right) {
            int order = compare(left, right);
            return new ContainerKey(order <= 0 ? left : right, order <= 0 ? right : left);
        }

        Set<PaperTrackingScanRequest.ChunkReference> chunks() {
            Set<PaperTrackingScanRequest.ChunkReference> result = new HashSet<>();
            result.add(first.chunk());
            if (second != null) result.add(second.chunk());
            return result;
        }

        boolean contains(BlockKey block) {
            return first.equals(block) || block.equals(second);
        }

        private static int compare(BlockKey a, BlockKey b) {
            int x = Integer.compare(a.x(), b.x());
            if (x != 0) return x;
            int y = Integer.compare(a.y(), b.y());
            return y != 0 ? y : Integer.compare(a.z(), b.z());
        }
    }

    private static final class Entry {
        long lastVerifiedTick;
        long lastAttemptTick = Long.MIN_VALUE;
        boolean accessible = true;
        boolean urgent;
        boolean unresolved;
    }

    @SuppressWarnings("PMD.UseConcurrentHashMap") // Accessed only on Paper's main thread.
    private final Map<ContainerKey, Entry> entries = new HashMap<>();
    @SuppressWarnings("PMD.UseConcurrentHashMap") // Main-thread chunk lifecycle only.
    private final Map<PaperTrackingScanRequest.ChunkReference, Set<ContainerKey>> byChunk =
            new HashMap<>();
    @SuppressWarnings("PMD.UseConcurrentHashMap") // Main-thread container events only.
    private final Map<BlockKey, Set<ContainerKey>> byBlock = new HashMap<>();
    private final Set<ContainerKey> roundRobin = new LinkedHashSet<>();
    private final Set<ContainerKey> urgent = new LinkedHashSet<>();
    private int peakUrgentDepth;

    void observed(ContainerKey key, boolean evidence, boolean complete, long tick) {
        Objects.requireNonNull(key, "key");
        if (complete && !evidence) {
            remove(key);
            return;
        }
        if (!evidence && !entries.containsKey(key)) return;
        Entry entry = entries.computeIfAbsent(key, this::register);
        entry.accessible = true;
        entry.lastAttemptTick = tick;
        if (complete) {
            entry.lastVerifiedTick = tick;
            entry.unresolved = false;
        } else {
            entry.unresolved = true;
        }
        entry.urgent = false;
    }

    boolean recentlyVerified(ContainerKey key, long tick) {
        Entry entry = entries.get(key);
        return entry != null && !entry.unresolved && entry.lastVerifiedTick == tick;
    }

    void chunkUnloaded(PaperTrackingScanRequest.ChunkReference chunk) {
        for (ContainerKey key : byChunk.getOrDefault(chunk, Set.of())) {
            Entry entry = entries.get(key);
            if (entry == null) continue;
            entry.accessible = false;
            entry.urgent = false;
        }
    }

    void chunkLoaded(
            PaperTrackingScanRequest.ChunkReference chunk,
            Predicate<PaperTrackingScanRequest.ChunkReference> naturallyLoaded) {
        for (ContainerKey key : byChunk.getOrDefault(chunk, Set.of())) {
            Entry entry = entries.get(key);
            if (entry == null || !key.chunks().stream().allMatch(naturallyLoaded)) continue;
            entry.accessible = true;
            if (!entry.urgent) {
                entry.urgent = true;
                urgent.add(key);
                peakUrgentDepth = Math.max(peakUrgentDepth, urgent.size());
            }
        }
    }

    ContainerKey nextWork(
            long tick,
            long cadence,
            Predicate<PaperTrackingScanRequest.ChunkReference> naturallyLoaded) {
        ContainerKey pending = nextUrgent(tick, naturallyLoaded);
        return pending != null ? pending : nextPeriodic(tick, cadence, naturallyLoaded);
    }

    private ContainerKey nextUrgent(
            long tick, Predicate<PaperTrackingScanRequest.ChunkReference> loaded) {
        int attempts = Math.min(32, urgent.size());
        for (int n = 0; n < attempts; n++) {
            ContainerKey key = takeFirst(urgent);
            Entry entry = entries.get(key);
            if (entry == null || !entry.urgent) continue;
            entry.urgent = false;
            if (canRun(key, entry, tick, loaded)) return key;
        }
        return null;
    }

    private ContainerKey nextPeriodic(
            long tick, long cadence,
            Predicate<PaperTrackingScanRequest.ChunkReference> loaded) {
        int attempts = Math.min(32, roundRobin.size());
        ContainerKey selected = null;
        for (int n = 0; n < attempts && selected == null; n++) {
            ContainerKey key = takeFirst(roundRobin);
            Entry entry = entries.get(key);
            if (entry != null) {
                roundRobin.add(key);
                long last = Math.max(entry.lastVerifiedTick, entry.lastAttemptTick);
                if (tick - last >= cadence && canRun(key, entry, tick, loaded)) {
                    selected = key;
                }
            }
        }
        return selected;
    }

    private static ContainerKey takeFirst(Set<ContainerKey> queue) {
        if (queue.isEmpty()) return null;
        var iterator = queue.iterator();
        ContainerKey key = iterator.next();
        iterator.remove();
        return key;
    }

    private boolean canRun(
            ContainerKey key, Entry entry, long tick,
            Predicate<PaperTrackingScanRequest.ChunkReference> loaded) {
        if (entry.lastAttemptTick == tick) return false;
        if (!key.chunks().stream().allMatch(loaded)) {
            entry.accessible = false;
            return false;
        }
        entry.accessible = true;
        entry.lastAttemptTick = tick;
        return true;
    }

    Set<BlockKey> blockChanged(BlockKey block) {
        Set<BlockKey> survivors = new LinkedHashSet<>();
        for (ContainerKey key : Set.copyOf(byBlock.getOrDefault(block, Set.of()))) {
            if (!key.contains(block)) continue;
            if (key.second() != null) {
                survivors.add(key.first().equals(block) ? key.second() : key.first());
            }
            remove(key);
        }
        return survivors;
    }

    private Entry register(ContainerKey key) {
        Entry entry = new Entry();
        roundRobin.add(key);
        for (PaperTrackingScanRequest.ChunkReference chunk : key.chunks()) {
            byChunk.computeIfAbsent(chunk, PaperLoadedTrackedContainerIndex::newChunkGroup)
                    .add(key);
        }
        byBlock.computeIfAbsent(key.first(), ignored -> new HashSet<>()).add(key);
        if (key.second() != null) {
            byBlock.computeIfAbsent(key.second(), ignored -> new HashSet<>()).add(key);
        }
        return entry;
    }

    private static Set<ContainerKey> newChunkGroup(
            PaperTrackingScanRequest.ChunkReference unused) {
        return new HashSet<>();
    }

    private void remove(ContainerKey key) {
        if (entries.remove(key) == null) return;
        for (PaperTrackingScanRequest.ChunkReference chunk : key.chunks()) {
            Set<ContainerKey> containers = byChunk.get(chunk);
            if (containers == null) continue;
            containers.remove(key);
            if (containers.isEmpty()) byChunk.remove(chunk);
        }
        forgetBlock(key.first(), key);
        if (key.second() != null) forgetBlock(key.second(), key);
        roundRobin.remove(key);
        urgent.remove(key);
    }

    private void forgetBlock(BlockKey block, ContainerKey key) {
        Set<ContainerKey> containers = byBlock.get(block);
        if (containers == null) return;
        containers.remove(key);
        if (containers.isEmpty()) byBlock.remove(block);
    }

    boolean containsBlock(BlockKey block) {
        return byBlock.containsKey(block);
    }

    int size() {
        return entries.size();
    }

    int unresolvedCount() {
        int result = 0;
        for (Entry entry : entries.values()) {
            if (entry.unresolved || !entry.accessible) result++;
        }
        return result;
    }

    int pendingCount() {
        return urgent.size();
    }

    int peakPending() {
        return peakUrgentDepth;
    }

    void clear() {
        entries.clear();
        byChunk.clear();
        byBlock.clear();
        roundRobin.clear();
        urgent.clear();
        peakUrgentDepth = 0;
    }
}
