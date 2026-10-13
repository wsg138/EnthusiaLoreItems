package net.enthusia.loreitems.paper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntFunction;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.domain.LocationDescriptor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Resumable player/cursor/open-container access without retaining Bukkit
 * objects between ticks. Callers rebuild Sources from the *current* naturally
 * accessible state on every page; Cursor carries only immutable descriptors.
 *
 * This helper never makes authoritative or uniqueness claims. The event
 * listener must schedule and fairly drain pending cursors under a tick budget.
 */
final class PaperPagedInventoryAccess {
    private static final String SLOT = "slot:";
    private static final int MAX_SOURCES = 16;
    private final PaperBoundedInventoryContinuation traversal =
            new PaperBoundedInventoryContinuation();

    record Shape(LocationDescriptor.Type type, String key, String geometry,
                 String prefix, int size) {}

    record Source(Shape shape, IntFunction<ItemStack> items) {
        Source {
            Objects.requireNonNull(shape, "shape");
            Objects.requireNonNull(items, "items");
            if (shape.size() < 0 || shape.size() > PaperBoundedInventoryContinuation.MAX_ROOTS) {
                throw new IllegalArgumentException("invalid root size");
            }
            Objects.requireNonNull(shape.type(), "type");
            Objects.requireNonNull(shape.key(), "key");
            Objects.requireNonNull(shape.geometry(), "geometry");
            Objects.requireNonNull(shape.prefix(), "prefix");
        }
    }

    /** Rebuilt from live post-event state on every page, never saved in a cursor. */
    static List<Source> playerSources(Player player) {
        String key = "player:" + player.getUniqueId();
        ItemStack[] storage = player.getInventory().getStorageContents();
        ItemStack[] armor = player.getInventory().getArmorContents();
        ItemStack offhand = player.getInventory().getItemInOffHand();
        ItemStack cursor = player.getItemOnCursor();
        ItemStack[] ender = player.getEnderChest().getContents();
        return List.of(
                new Source(new Shape(LocationDescriptor.Type.PLAYER_INVENTORY,
                        key, "storage", SLOT, storage.length), i -> storage[i]),
                new Source(new Shape(LocationDescriptor.Type.PLAYER_INVENTORY,
                        key, "armor", "armor:", armor.length), i -> armor[i]),
                new Source(new Shape(LocationDescriptor.Type.PLAYER_INVENTORY,
                        key, "offhand", "offhand", 1), i -> offhand),
                new Source(new Shape(LocationDescriptor.Type.PLAYER_INVENTORY,
                        key, "cursor", "cursor", 1), i -> cursor),
                new Source(new Shape(LocationDescriptor.Type.PLAYER_ENDER_CHEST,
                        key, "ender", SLOT, ender.length), i -> ender[i]));
    }

    static Source containerSource(Inventory inventory,
                                  LocationDescriptor.Type type, String key,
                                  String geometry) {
        return new Source(new Shape(type, key, geometry, SLOT, inventory.getSize()),
                inventory::getItem);
    }

    record Cursor(int sourceIndex, PaperBoundedInventoryContinuation.Cursor nested,
                  List<Shape> shapes, long generation, long mutationFence,
                  boolean unresolved) {
        Cursor {
            shapes = List.copyOf(shapes);
            if (sourceIndex < 0 || sourceIndex >= shapes.size()) {
                throw new IllegalArgumentException("invalid source index");
            }
        }
    }

    record Batch(Map<LoreItemIdentity, List<LocationDescriptor>> observations,
                 PaperBoundedInventoryContinuation.State state, Cursor next,
                 int visited) {
        Batch {
            Map<LoreItemIdentity, List<LocationDescriptor>> copy = new LinkedHashMap<>();
            observations.forEach((id, paths) -> copy.put(id, List.copyOf(paths)));
            observations = Map.copyOf(copy);
        }

        boolean coverageComplete() {
            return state == PaperBoundedInventoryContinuation.State.COMPLETE;
        }
    }

    Batch scan(List<Source> sources, long generation, long mutationFence,
               Cursor cursor, PaperScanLimit limit) {
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(limit, "limit");
        if (sources.size() > MAX_SOURCES) {
            return batch(Map.of(), PaperBoundedInventoryContinuation.State.UNRESOLVED,
                    null, 0);
        }
        List<Shape> shapes = sources.stream().map(Source::shape).toList();
        if (cursor != null && !matching(cursor, shapes, generation, mutationFence)) {
            return batch(Map.of(), PaperBoundedInventoryContinuation.State.INVALIDATED,
                    null, 0);
        }
        return scanSources(sources, shapes, generation, mutationFence, cursor, limit);
    }

    private Batch scanSources(List<Source> sources, List<Shape> shapes,
                              long generation, long mutationFence,
                              Cursor cursor, PaperScanLimit limit) {
        Map<LoreItemIdentity, List<LocationDescriptor>> observed = new LinkedHashMap<>();
        boolean unresolved = cursor != null && cursor.unresolved();
        int start = cursor == null ? 0 : cursor.sourceIndex();
        int visited = 0;
        for (int index = start; index < sources.size(); index++) {
            if (!limit.canVisit()) {
                return paused(observed, shapes, generation, mutationFence,
                        index, null, unresolved, visited);
            }
            Source source = sources.get(index);
            PaperBoundedInventoryContinuation.Cursor nested =
                    index == start && cursor != null ? cursor.nested() : null;
            var page = scanSource(source, generation, mutationFence, nested, limit);
            append(observed, source.shape(), page.observations());
            visited += page.visited();
            unresolved |= page.malformedEvidence();
            if (page.state() == PaperBoundedInventoryContinuation.State.INVALIDATED) {
                return batch(observed, page.state(), null, visited);
            }
            if (page.nextCursor() != null) {
                return paused(observed, shapes, generation, mutationFence,
                        index, page.nextCursor(), unresolved, visited);
            }
        }
        return batch(observed, unresolved
                ? PaperBoundedInventoryContinuation.State.UNRESOLVED
                : PaperBoundedInventoryContinuation.State.COMPLETE, null, visited);
    }

    private static Batch paused(
            Map<LoreItemIdentity, List<LocationDescriptor>> observed,
            List<Shape> shapes, long generation, long mutationFence, int index,
            PaperBoundedInventoryContinuation.Cursor nested,
            boolean unresolved, int visited) {
        Cursor next = new Cursor(index, nested, shapes, generation, mutationFence, unresolved);
        return batch(observed, PaperBoundedInventoryContinuation.State.PARTIAL,
                next, visited);
    }

    private PaperBoundedInventoryContinuation.Page scanSource(
            Source source, long generation, long mutationFence,
            PaperBoundedInventoryContinuation.Cursor cursor, PaperScanLimit limit) {
        Shape shape = source.shape();
        return traversal.scanSlots(shape.size(), source.items(), shape.type(),
                shape.key(), generation, mutationFence,
                shape.geometry(), cursor, limit);
    }

    private static boolean matching(Cursor cursor, List<Shape> shapes,
                                    long generation, long mutationFence) {
        return cursor.shapes().equals(shapes)
                && cursor.generation() == generation
                && cursor.mutationFence() == mutationFence;
    }

    private static void append(
            Map<LoreItemIdentity, List<LocationDescriptor>> result, Shape shape,
            Map<LoreItemIdentity, List<LocationDescriptor>> observations) {
        observations.forEach((id, paths) -> {
            List<LocationDescriptor> destinations =
                    result.computeIfAbsent(id, ignored -> new ArrayList<>());
            paths.forEach(location -> destinations.add(remap(location, shape.prefix())));
        });
    }

    /** Preserve nested location keys while rewriting player armor/offhand/cursor paths. */
    private static LocationDescriptor remap(LocationDescriptor source, String prefix) {
        if (SLOT.equals(prefix)) {
            return source;
        }
        String path = source.containerPath();
        int slash = path.indexOf('/');
        String root = slash < 0 ? path : path.substring(0, slash);
        String suffix = slash < 0 ? "" : path.substring(slash);
        String slot = root.substring(SLOT.length());
        String pathName = prefix.endsWith(":") ? prefix + slot + suffix : prefix + suffix;
        return new LocationDescriptor(source.type(), source.locationKey(), pathName);
    }

    private static Batch batch(Map<LoreItemIdentity, List<LocationDescriptor>> observed,
                               PaperBoundedInventoryContinuation.State state,
                               Cursor next, int visited) {
        return new Batch(observed, state, next, visited);
    }
}
