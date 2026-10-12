package net.enthusia.loreitems.paper;

import java.util.ArrayDeque;
import java.util.HashMap;
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
        boolean retryListed;
        boolean observedTracked;
        long lastVerifiedTick;
        long nextRetryTick;

        Entry(long generation) {
            this.generation = generation;
        }
    }

    private final Map<PaperTrackingScanRequest.ChunkReference, Entry> loaded = new HashMap<>();
    private final ArrayDeque<Pending> retry = new ArrayDeque<>();
    private final ArrayDeque<PaperTrackingScanRequest.ChunkReference> tracked = new ArrayDeque<>();
    private long nextGeneration;

    long observeLoad(PaperTrackingScanRequest.ChunkReference key) {
        Objects.requireNonNull(key, "key");
        Entry present = loaded.get(key);
        if (present != null && present.state != State.INACCESSIBLE) {
            return 0L;
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
        if (!entry.retryListed) {
            retry.addLast(pending);
            entry.retryListed = true;
        }
    }

    Pending nextDirty(long tick) {
        int attempts = Math.min(16, retry.size());
        while (attempts-- > 0 && !retry.isEmpty()) {
            Pending pending = retry.removeFirst();
            Entry entry = current(pending);
            if (entry != null) {
                entry.retryListed = false;
                if (entry.state == State.DIRTY) {
                    if (tick < entry.nextRetryTick) {
                        retry.addLast(pending);
                        entry.retryListed = true;
                        continue;
                    }
                    return pending;
                }
            }
        }
        return null;
    }

    void unload(PaperTrackingScanRequest.ChunkReference key) {
        loaded.remove(key);
        // Stale generation tokens are harmless and discarded before being queued.
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
        while (candidates-- > 0) {
            PaperTrackingScanRequest.ChunkReference key = tracked.removeFirst();
            Entry entry = loaded.get(key);
            if (entry == null || !entry.observedTracked) {
                continue;
            }
            tracked.addLast(key);
            if (entry.state == State.COMPLETE
                    && tick - entry.lastVerifiedTick >= cadence) {
                entry.state = State.DIRTY;
                return new Pending(key, entry.generation);
            }
        }
        return null;
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
