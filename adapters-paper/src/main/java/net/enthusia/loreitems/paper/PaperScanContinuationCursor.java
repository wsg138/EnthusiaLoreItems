package net.enthusia.loreitems.paper;

import java.io.Serial;
import java.io.Serializable;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Pure, immutable continuation state for a loaded-only inventory traversal.
 * No Bukkit references are retained; the caller must re-resolve each page on the server thread.
 * A completed traversal is NOT a durable acknowledgement or authoritative identity proof.
 */
final class PaperScanContinuationCursor {
    static final int MAX_ROOT_SLOTS = 8_192;
    static final int MAX_NESTING_DEPTH = 8;
    static final int MAX_PAGE_VISITS = 256;
    private static final long MAX_TOTAL_VISITS = 1_000_000L;

    private PaperScanContinuationCursor() { }

    enum State { INCOMPLETE, COMPLETE, INVALIDATED, UNAVAILABLE, REPLAYED }
    enum Admission { STORED, UPDATED, STALE_IGNORED, REDISCOVERY_REQUIRED }

    /** Canonical source identity must include a UUID-qualified physical inventory key. */
    record Scope(UUID worldId, String sourceKey, long loadGeneration,
                 long scanGeneration, String geometry, int rootSlots) implements Serializable {
        @Serial private static final long serialVersionUID = 1L;

        Scope {
            Objects.requireNonNull(worldId, "worldId");
            Objects.requireNonNull(sourceKey, "sourceKey");
            Objects.requireNonNull(geometry, "geometry");
            if (sourceKey.isBlank() || geometry.isBlank()
                    || loadGeneration <= 0 || scanGeneration < 0
                    || rootSlots < 1 || rootSlots > MAX_ROOT_SLOTS) {
                throw new IllegalArgumentException("invalid scan scope");
            }
        }
    }

    /** The producer must bump either version or dirty epoch on any relevant mutation. */
    record Stamp(Scope scope, long inventoryVersion, long dirtyEpoch,
                 boolean accessible) implements Serializable {
        @Serial private static final long serialVersionUID = 1L;

        Stamp {
            Objects.requireNonNull(scope, "scope");
            if (inventoryVersion < 0 || dirtyEpoch < 0) {
                throw new IllegalArgumentException("negative revision");
            }
        }
    }

    /** An index and width at each depth; all frames are coordinates, not live items. */
    record Frame(String kind, int slot, int width) implements Serializable {
        @Serial private static final long serialVersionUID = 1L;

        Frame {
            Objects.requireNonNull(kind, "kind");
            if (kind.isBlank() || slot < 0 || width < 1
                    || width > MAX_ROOT_SLOTS || slot >= width) {
                throw new IllegalArgumentException("invalid path frame");
            }
        }
    }

    record Cursor(Stamp stamp, List<Frame> path, long visits,
                  int pages, boolean complete) implements Serializable {
        @Serial private static final long serialVersionUID = 1L;

        Cursor {
            Objects.requireNonNull(stamp, "stamp");
            path = List.copyOf(path);
            if (!stamp.accessible() || visits < 0 || visits > MAX_TOTAL_VISITS
                    || pages < 0 || (complete != path.isEmpty())) {
                throw new IllegalArgumentException("invalid continuation");
            }
            if (!complete) {
                validatePath(stamp.scope(), path);
            }
        }
    }

    record Outcome(State state, Cursor cursor) {
        Outcome {
            Objects.requireNonNull(state, "state");
        }

        /** No pure traversal result alone can authorize absence/presence or a SQL ACK. */
        boolean authoritativeEligible() {
            return false;
        }
    }

    static Cursor begin(Stamp stamp) {
        Objects.requireNonNull(stamp, "stamp");
        if (!stamp.accessible()) {
            throw new IllegalArgumentException("source must be naturally accessible");
        }
        return new Cursor(stamp,
                List.of(new Frame("root", 0, stamp.scope().rootSlots())), 0, 0, false);
    }

    /**
     * Advance after one bounded page. nextPath is the NEXT unvisited coordinate;
     * a terminal page must pass an empty path. Mutated/unloaded scopes are discarded.
     */
    static Outcome advance(Cursor cursor, Stamp observed, List<Frame> nextPath,
                           int visited, boolean finished) {
        Objects.requireNonNull(cursor, "cursor");
        Objects.requireNonNull(observed, "observed");
        if (!observed.accessible()) {
            return new Outcome(State.UNAVAILABLE, null);
        }
        if (!cursor.stamp().equals(observed)) {
            return new Outcome(State.INVALIDATED, null);
        }
        if (cursor.complete()) {
            return new Outcome(State.REPLAYED, cursor);
        }
        if (visited < 1 || visited > MAX_PAGE_VISITS
                || cursor.visits() > MAX_TOTAL_VISITS - visited) {
            throw new IllegalArgumentException("page visit bound");
        }
        List<Frame> next = List.copyOf(nextPath);
        if (finished) {
            if (!next.isEmpty()) {
                throw new IllegalArgumentException("terminal page has next slot");
            }
        } else {
            validatePath(observed.scope(), next);
            if (!forward(cursor.path(), next)) {
                throw new IllegalArgumentException("page did not advance");
            }
        }
        Cursor advanced = new Cursor(observed, next, cursor.visits() + visited,
                cursor.pages() + 1, finished);
        return new Outcome(finished ? State.COMPLETE : State.INCOMPLETE, advanced);
    }

    private static void validatePath(Scope scope, List<Frame> path) {
        if (path.isEmpty() || path.size() > MAX_NESTING_DEPTH + 1
                || !"root".equals(path.get(0).kind())
                || path.get(0).width() != scope.rootSlots()) {
            throw new IllegalArgumentException("invalid root/path depth");
        }
        for (int i = 1; i < path.size(); i++) {
            if ("root".equals(path.get(i).kind())) {
                throw new IllegalArgumentException("nested root frame");
            }
        }
    }

    private static boolean forward(List<Frame> previous, List<Frame> next) {
        int common = Math.min(previous.size(), next.size());
        for (int i = 0; i < common; i++) {
            Frame before = previous.get(i);
            Frame after = next.get(i);
            if (before.width() != after.width() || !before.kind().equals(after.kind())) {
                throw new IllegalArgumentException("nested topology changed");
            }
            if (after.slot() != before.slot()) {
                return after.slot() > before.slot();
            }
        }
        return next.size() > previous.size();
    }

    record SourceKey(UUID worldId, String sourceKey) {
        SourceKey {
            Objects.requireNonNull(worldId, "worldId");
            Objects.requireNonNull(sourceKey, "sourceKey");
        }

        static SourceKey of(Cursor cursor) {
            Scope scope = cursor.stamp().scope();
            return new SourceKey(scope.worldId(), scope.sourceKey());
        }
    }

    /**
     * Main-thread-only, bounded, round-robin retry index. next() never consumes an
     * entry: failed admission remains pending. A full index latches a global
     * rediscovery obligation, which only an external complete natural-loaded sweep
     * can discharge. This does not itself implement that sweep or durable SQL ACK.
     */
    static final class FairRetries {
        private final int capacity;
        private final LinkedHashMap<SourceKey, Cursor> pending = new LinkedHashMap<>();
        private boolean rediscoveryRequired;

        FairRetries(int capacity) {
            if (capacity < 1 || capacity > MAX_ROOT_SLOTS) {
                throw new IllegalArgumentException("invalid retry capacity");
            }
            this.capacity = capacity;
        }

        Admission offer(Cursor cursor) {
            Objects.requireNonNull(cursor, "cursor");
            SourceKey key = SourceKey.of(cursor);
            Cursor previous = pending.get(key);
            if (previous != null) {
                if (stale(previous, cursor)) {
                    return Admission.STALE_IGNORED;
                }
                Cursor accepted = previous.stamp().equals(cursor.stamp())
                        ? cursor : begin(cursor.stamp());
                pending.put(key, accepted);
                return Admission.UPDATED;
            }
            if (pending.size() >= capacity) {
                rediscoveryRequired = true;
                return Admission.REDISCOVERY_REQUIRED;
            }
            pending.put(key, cursor);
            return Admission.STORED;
        }

        private boolean stale(Cursor old, Cursor newer) {
            Scope a = old.stamp().scope();
            Scope b = newer.stamp().scope();
            if (b.loadGeneration() < a.loadGeneration()
                    || (b.loadGeneration() == a.loadGeneration()
                        && b.scanGeneration() < a.scanGeneration())) {
                return true;
            }
            if (b.loadGeneration() != a.loadGeneration()
                    || b.scanGeneration() != a.scanGeneration()) {
                return false;
            }
            if (b.inventoryVersion() < 0) {
                return true;
            }
            if (newer.stamp().inventoryVersion() < old.stamp().inventoryVersion()
                    || newer.stamp().dirtyEpoch() < old.stamp().dirtyEpoch()) {
                return true;
            }
            if (newer.stamp().equals(old.stamp())) {
                return newer.visits() < old.visits()
                        || newer.pages() < old.pages()
                        || (old.complete() && !newer.complete());
            }
            return false;
        }

        /** Round-robin peek; never loses work if the next admission fails. */
        Cursor next() {
            if (pending.isEmpty()) {
                return null;
            }
            Iterator<Map.Entry<SourceKey, Cursor>> it = pending.entrySet().iterator();
            Map.Entry<SourceKey, Cursor> entry = it.next();
            SourceKey key = entry.getKey();
            Cursor cursor = entry.getValue();
            it.remove();
            pending.put(key, cursor);
            return cursor;
        }

        /** Must be called ONLY after an externally verified durable complete ACK. */
        boolean acknowledge(Cursor completed) {
            Objects.requireNonNull(completed, "completed");
            SourceKey key = SourceKey.of(completed);
            if (!completed.complete() || !completed.equals(pending.get(key))) {
                return false;
            }
            pending.remove(key);
            return true;
        }

        int size() {
            return pending.size();
        }

        boolean rediscoveryRequired() {
            return rediscoveryRequired;
        }

        /** A partial/failed global sweep must NOT clear a saturated dirty alarm. */
        void reportFullRediscovery(boolean fullyVisitedNaturallyLoadedSources) {
            if (fullyVisitedNaturallyLoadedSources) {
                rediscoveryRequired = false;
            }
        }
    }
}
