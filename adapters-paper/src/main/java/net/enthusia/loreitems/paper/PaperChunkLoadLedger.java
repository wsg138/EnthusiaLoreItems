package net.enthusia.loreitems.paper;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Main-thread natural-load ledger. Keeps identifiers only, never Bukkit world or chunk
 * references. One entry exists per observed loaded chunk; unload invalidates its generation.
 * Unadmitted work remains DIRTY and must be offered again when queue capacity returns.
 */
final class PaperChunkLoadLedger {
    enum State { DIRTY, QUEUED, RUNNING, COMPLETE, INACCESSIBLE }

    record Pending(PaperTrackingScanRequest.ChunkReference key, long generation) { }

    private static final class Entry {
        final long generation;
        State state = State.DIRTY;
        boolean observedTracked;
        long lastVerifiedTick;
        long nextRetryTick;

        Entry(long generation) {
            this.generation = generation;
        }
    }

    // The ledger is accessed only from the Paper server thread; preserve predictable iteration.
    private final Map<PaperTrackingScanRequest.ChunkReference, Entry> loaded =
            new LinkedHashMap<>();
    // A keyed FIFO prevents stale generation tokens from accumulating on churn.
    // Invariant: no more than one retry token per currently observed loaded chunk.
    private final LinkedHashMap<PaperTrackingScanRequest.ChunkReference, Pending> retry =
            new LinkedHashMap<>();
    private final ArrayDeque<PaperTrackingScanRequest.ChunkReference> tracked = new ArrayDeque<>();
    private long nextGeneration;

    long observeLoad(PaperTrackingScanRequest.ChunkReference key) {
        Objects.requireNonNull(key, "key");
        Entry present = loaded.get(key);
        if (present != null && present.state != State.INACCESSIBLE) {
            return 0L;
        }
        // A fresh generation must not inherit stale retry or tracked verification
        // keys from an inaccessible older generation.
        if (present != null) {
            retry.remove(key);
            tracked.remove(key);
        }
        Entry entry = new Entry(++nextGeneration);
        loaded.put(key, entry);
        return entry.generation;
    }

    boolean queued(Pending pending) {
        Entry entry = current(pending);
        if (entry == null || entry.state != State.DIRTY) {
            return false;
        }
        entry.state = State.QUEUED;
        return true;
    }

    boolean begin(Pending pending) {
        Entry entry = current(pending);
        if (entry == null || entry.state != State.QUEUED) {
            return false;
        }
        entry.state = State.RUNNING;
        return true;
    }

    void complete(Pending pending, boolean complete, boolean accessible, long tick) {
        Entry entry = current(pending);
        if (entry == null) {
            return;
        }
        if (!accessible) {
            entry.state = State.INACCESSIBLE;
            // The inaccessible generation cannot be verified on a future tick.
            // A new naturally loaded generation must explicitly register again.
            retry.remove(pending.key());
            tracked.remove(pending.key());
            entry.observedTracked = false;
        } else if (complete) {
            entry.state = State.COMPLETE;
            entry.lastVerifiedTick = tick;
        } else {
            entry.nextRetryTick = tick + 1200L;
            markDirty(pending);
        }
    }

    void markDirty(Pending pending) {
        Entry entry = current(pending);
        if (entry == null) {
            return;
        }
        entry.state = State.DIRTY;
        retry.putIfAbsent(pending.key(), pending);
    }

    Pending nextDirty(long tick) {
        int maximumAttempts = Math.min(16, retry.size());
        Pending eligible = null;
        for (int attempt = 0;
                attempt < maximumAttempts && !retry.isEmpty() && eligible == null;
                attempt++) {
            var iterator = retry.entrySet().iterator();
            Pending pending = iterator.next().getValue();
            iterator.remove();
            Entry entry = current(pending);
            if (entry != null && entry.state == State.DIRTY) {
                if (tick < entry.nextRetryTick) {
                    // Fair rotation without retaining another token.
                    retry.put(pending.key(), pending);
                } else {
                    eligible = pending;
                }
            }
        }
        return eligible;
    }

    void unload(PaperTrackingScanRequest.ChunkReference key) {
        loaded.remove(key);
        // Promptly forget the obsolete generation; do not accumulate stale tokens.
        retry.remove(key);
        tracked.remove(key);
    }

    boolean currentGeneration(Pending pending) {
        return current(pending) != null;
    }

    void observeTracked(PaperTrackingScanRequest.ChunkReference key) {
        Entry entry = loaded.get(key);
        if (entry != null && !entry.observedTracked) {
            entry.observedTracked = true;
            tracked.addLast(key);
        }
    }

    void forgetTracked(PaperTrackingScanRequest.ChunkReference key) {
        Entry entry = loaded.get(key);
        if (entry != null) {
            entry.observedTracked = false;
        }
        tracked.remove(key);
    }

    Pending nextTrackedDue(long tick, long cadence) {
        int candidates = Math.min(32, tracked.size());
        PaperTrackingScanRequest.ChunkReference dueKey = null;
        long dueGeneration = 0L;
        for (int attempt = 0; attempt < candidates && dueKey == null; attempt++) {
            PaperTrackingScanRequest.ChunkReference key = tracked.removeFirst();
            Entry entry = loaded.get(key);
            if (entry == null || !entry.observedTracked) {
                continue;
            }
            tracked.addLast(key);
            if (entry.state == State.COMPLETE
                    && tick - entry.lastVerifiedTick >= cadence) {
                entry.state = State.DIRTY;
                dueKey = key;
                dueGeneration = entry.generation;
            }
        }
        return dueKey == null ? null : new Pending(dueKey, dueGeneration);
    }

    int dirtyCount() {
        int count = 0;
        for (Entry entry : loaded.values()) {
            if (entry.state == State.DIRTY) {
                count++;
            }
        }
        return count;
    }

    int loadedCount() {
        return loaded.size();
    }

    int pendingRetryCount() {
        return retry.size();
    }

    int pendingTrackedCount() {
        return tracked.size();
    }

    void clear() {
        loaded.clear();
        retry.clear();
        tracked.clear();
    }

    private Entry current(Pending pending) {
        Entry entry = loaded.get(pending.key());
        return entry != null && entry.generation == pending.generation() ? entry : null;
    }

    static PaperTrackingScanRequest.ChunkReference key(UUID worldId, int x, int z) {
        return new PaperTrackingScanRequest.ChunkReference(worldId, x, z);
    }
}
