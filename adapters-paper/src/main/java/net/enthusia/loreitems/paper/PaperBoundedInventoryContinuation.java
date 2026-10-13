package net.enthusia.loreitems.paper;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntFunction;
import net.enthusia.loreitems.application.ItemIdentityReadResult;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.domain.LocationDescriptor;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * One bounded DFS page of an inventory. Cursors hold only immutable coordinates,
 * never Bukkit objects. A caller must re-resolve its loaded inventory and supply
 * its current lifecycle generation and pair/geometry signature for every page.
 *
 * A multi-page result is intentionally NEVER authoritative: a generation supplied
 * by an event listener cannot prove third-party mutations did not occur. Worker 07
 * must accumulate reconciliation evidence and implement a verified consistency
 * fence before promoting the entire traversal to unique/authoritative.
 */
final class PaperBoundedInventoryContinuation {
    static final int MAX_DEPTH = 8;
    static final int MAX_ROOTS = 8192;
    private static final String ROOT = "slot";
    private final PaperTrackedItemCollector collector = new PaperTrackedItemCollector();

    enum State { COMPLETE, PARTIAL, INVALIDATED, UNRESOLVED }

    record Frame(String kind, int index, int width) {
        Frame {
            Objects.requireNonNull(kind, "kind");
            if (index < 0 || width < 1 || index >= width) {
                throw new IllegalArgumentException("invalid frame");
            }
        }
    }

    record Cursor(String sourceId, long generation, String geometry,
                  int rootSize, List<Frame> path) {
        Cursor {
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(geometry, "geometry");
            path = List.copyOf(path);
            if (path.isEmpty() || path.size() > MAX_DEPTH + 1
                    || rootSize < 1 || rootSize > MAX_ROOTS
                    || !ROOT.equals(path.getFirst().kind())
                    || path.getFirst().width() != rootSize) {
                throw new IllegalArgumentException("invalid cursor");
            }
        }
    }

    record Page(Map<LoreItemIdentity, List<LocationDescriptor>> observations,
                State state, Cursor nextCursor, int visited, boolean authoritativeEligible) {
        Page {
            Map<LoreItemIdentity, List<LocationDescriptor>> immutable = new ConcurrentHashMap<>();
            observations.forEach((id, locations) -> immutable.put(id, List.copyOf(locations)));
            observations = Map.copyOf(immutable);
        }
        boolean complete() {
            return state == State.COMPLETE;
        }
    }

    Page scan(Inventory inventory, LocationDescriptor.Type type, String key,
              long generation, String geometry, Cursor cursor, PaperScanLimit limit) {
        if (inventory == null) {
            return outcome(Map.of(), State.UNRESOLVED, null, 0, false);
        }
        try {
            return scanSlots(inventory.getSize(), inventory::getItem, type, key,
                    generation, geometry, cursor, limit);
        } catch (RuntimeException inaccessible) {
            return outcome(Map.of(), State.UNRESOLVED, null, 0, false);
        }
    }

    Page scanSlots(int size, IntFunction<ItemStack> slots, LocationDescriptor.Type type,
                   String key, long generation, String geometry, Cursor cursor,
                   PaperScanLimit limit) {
        Objects.requireNonNull(slots, "slots");
        Objects.requireNonNull(limit, "limit");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(geometry, "geometry");
        if (size < 0 || size > MAX_ROOTS) {
            return outcome(Map.of(), State.UNRESOLVED, null, 0, false);
        }
        String source = type.name() + ':' + key;
        if (cursor != null && !sameSource(cursor, source, generation, geometry, size)) {
            return outcome(Map.of(), State.INVALIDATED, null, 0, false);
        }
        if (size == 0) {
            return outcome(Map.of(), State.COMPLETE, null, 0, cursor == null);
        }
        List<Frame> path = cursor == null
                ? new ArrayList<>(List.of(new Frame(ROOT, 0, size)))
                : new ArrayList<>(cursor.path());
        Traversal traversal = new Traversal(slots, type, key, generation,
                geometry, source, cursor == null);
        return traverse(traversal, path, limit);
    }

    private Page traverse(Traversal context, List<Frame> path, PaperScanLimit limit) {
        Map<LoreItemIdentity, List<LocationDescriptor>> observations =
                new ConcurrentHashMap<>();
        int visited = 0;
        // Page-local only: never keep Bukkit objects in the cursor.
        List<Resolved> cache = new ArrayList<>(MAX_DEPTH + 1);
        try {
            while (!path.isEmpty() && limit.tryConsume()) {
                Resolved node = at(context.slots(), path, cache);
                visited++;
                if (!remember(context, node.item(), path, observations)) {
                    return outcome(observations, State.UNRESOLVED, null, visited, false);
                }
                Children children = node.children();
                if (children != null && children.size() > 0 && path.size() > MAX_DEPTH) {
                    return outcome(observations, State.UNRESOLVED, null, visited, false);
                }
                descendOrAdvance(path, children);
            }
        } catch (RuntimeException changedOrInaccessible) {
            // A parent path changed, source unloaded, or Bukkit rejected an access.
            return outcome(observations, State.UNRESOLVED, null, visited, false);
        }
        if (!path.isEmpty()) {
            Cursor next = new Cursor(context.source(), context.generation(),
                    context.geometry(), path.getFirst().width(), path);
            return outcome(observations, State.PARTIAL, next, visited, false);
        }
        return outcome(observations, State.COMPLETE, null, visited, context.firstPage());
    }

    /** Invalid PDC is unresolved evidence, never proof that an inventory is clean. */
    private boolean remember(Traversal context, ItemStack item, List<Frame> path,
                             Map<LoreItemIdentity, List<LocationDescriptor>> observations) {
        if (item == null || item.getType().isAir()) {
            return true;
        }
        ItemIdentityReadResult result = collector.identityResult(item);
        if (result instanceof ItemIdentityReadResult.Invalid) {
            return false;
        }
        if (result instanceof ItemIdentityReadResult.Tracked tracked) {
            LocationDescriptor location = location(context.type(), context.key(), path);
            observations.computeIfAbsent(tracked.identity(), ignored -> new ArrayList<>())
                    .add(location);
        }
        return true;
    }

    private static void descendOrAdvance(List<Frame> path, Children children) {
        if (children == null || children.size() == 0) {
            advance(path);
            return;
        }
        path.add(new Frame(children.kind(), 0, children.size()));
    }

    private record Traversal(IntFunction<ItemStack> slots, LocationDescriptor.Type type,
                             String key, long generation, String geometry, String source,
                             boolean firstPage) {}

    private static boolean sameSource(Cursor cursor, String source, long generation,
                                      String geometry, int size) {
        return cursor.sourceId().equals(source) && cursor.generation() == generation
                && cursor.geometry().equals(geometry) && cursor.rootSize() == size;
    }

    private static Resolved at(IntFunction<ItemStack> slots, List<Frame> path,
                               List<Resolved> cache) {
        for (int level = 0; level < path.size(); level++) {
            Frame frame = path.get(level);
            if (cache.size() > level && cache.get(level).frame().equals(frame)) {
                continue;
            }
            cache.subList(level, cache.size()).clear();
            cache.add(resolve(slots, frame, level, cache));
        }
        return cache.get(path.size() - 1);
    }

    private static Resolved resolve(IntFunction<ItemStack> slots, Frame frame,
                                    int level, List<Resolved> cache) {
        ItemStack item = level == 0
                ? slots.apply(frame.index()) : child(cache.get(level - 1), frame);
        return new Resolved(frame, item, nested(item));
    }

    private static ItemStack child(Resolved parent, Frame frame) {
        Children nested = parent.children();
        if (nested == null || !nested.kind().equals(frame.kind())
                || nested.size() != frame.width()) {
            throw new IllegalStateException("nested inventory changed during continuation");
        }
        return nested.item(frame.index());
    }

    private record Resolved(Frame frame, ItemStack item, Children children) {}

    private static LocationDescriptor location(LocationDescriptor.Type type, String key,
                                               List<Frame> path) {
        LocationDescriptor result = new LocationDescriptor(type, key,
                ROOT + ':' + path.getFirst().index());
        for (int i = 1; i < path.size(); i++) {
            Frame frame = path.get(i);
            result = PaperPhysicalInventoryScanner.nestedLocation(
                    result, frame.kind(), frame.index());
        }
        return result;
    }

    private static Children nested(ItemStack item) {
        if (!mayContainChildren(item)) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof BlockStateMeta blockMeta
                && blockMeta.getBlockState() instanceof ShulkerBox box) {
            Inventory inventory = box.getInventory();
            return new Children("shulker", inventory.getSize(), inventory::getItem);
        }
        if (meta instanceof BundleMeta bundle) {
            List<ItemStack> contents = bundle.getItems();
            return new Children("bundle", contents.size(), contents::get);
        }
        return null;
    }

    private static boolean mayContainChildren(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return false;
        }
        String material = item.getType().name();
        return material.endsWith("SHULKER_BOX") || material.endsWith("BUNDLE");
    }

    private static void advance(List<Frame> path) {
        for (int index = path.size() - 1; index >= 0; index--) {
            Frame frame = path.get(index);
            if (frame.index() + 1 < frame.width()) {
                path.set(index, new Frame(frame.kind(), frame.index() + 1, frame.width()));
                path.subList(index + 1, path.size()).clear();
                return;
            }
        }
        path.clear();
    }

    private static Page outcome(Map<LoreItemIdentity, List<LocationDescriptor>> observations,
                                State state, Cursor next, int visited, boolean authoritative) {
        return new Page(observations, state, next, visited, authoritative);
    }

    private record Children(String kind, int size, IntFunction<ItemStack> slots) {
        private ItemStack item(int index) {
            return slots.apply(index);
        }
    }
}
