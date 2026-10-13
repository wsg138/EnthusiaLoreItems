package net.enthusia.loreitems.paper;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.BlockFace;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Naturally loaded-only physical scanner; paired chests are one logical observation. */
final class PaperPhysicalChunkScanner {
    private static final int MAX_ITEMS_PER_SCAN = 256;
    private final PaperPhysicalInventoryScanner scanner;
    private final PaperPhysicalEntityScanner entityScanner;
    private final MetricsPort metrics;
    private final Logger logger;
    private final PaperLoadedTrackedContainerIndex index;
    private final Set<PaperLoadedTrackedContainerIndex.ContainerKey> scannedThisTick =
            new HashSet<>();
    private long lastTick = Long.MIN_VALUE;

    PaperPhysicalChunkScanner(
            PaperPhysicalInventoryScanner scanner,
            PaperPhysicalEntityScanner entityScanner,
            MetricsPort metrics,
            Logger logger,
            PaperLoadedTrackedContainerIndex index) {
        this.scanner = scanner;
        this.entityScanner = entityScanner;
        this.metrics = metrics;
        this.logger = logger;
        this.index = index;
    }

    void invalidateLifecycleCache() {
        scannedThisTick.clear();
        lastTick = Long.MIN_VALUE;
    }

    boolean scanChunk(
            Chunk chunk, TrackingObservationUseCase.Presence presence,
            String source, long tick) {
        // Callers check natural chunk load state before invoking this scanner.
        prepareTick(tick);
        PaperScanLimit limit = new PaperScanLimit(MAX_ITEMS_PER_SCAN);
        entityScanner.scan(List.of(chunk.getEntities()), presence, source, limit);
        boolean complete = scanChunkContainers(chunk, presence, source, limit, tick);
        if (limit.hasRemaining() && complete) return true;
        metrics.increment("tracking.scan_truncated");
        logger.fine("Lore-item bounded chunk scan was incomplete for " + source + '.');
        return false;
    }

    private boolean scanChunkContainers(
            Chunk chunk, TrackingObservationUseCase.Presence presence,
            String source, PaperScanLimit limit, long tick) {
        boolean complete = true;
        for (BlockState state : chunk.getTileEntities(false)) {
            if (!limit.hasRemaining()) return false;
            if (!scanContainer(state, presence, source, limit, tick)) complete = false;
        }
        return complete;
    }

    boolean scanBlock(
            Plugin plugin, PaperLoadedTrackedContainerIndex.BlockKey block, long tick) {
        World world = plugin.getServer().getWorld(block.worldId());
        if (world == null || !world.isChunkLoaded(block.x() >> 4, block.z() >> 4)) {
            return false;
        }
        prepareTick(tick);
        BlockState state = world.getBlockAt(block.x(), block.y(), block.z()).getState(false);
        return scanContainer(state, TrackingObservationUseCase.Presence.PRESENT,
                "container-movement", new PaperScanLimit(MAX_ITEMS_PER_SCAN), tick);
    }

    static Optional<PaperLoadedTrackedContainerIndex.BlockKey> anchor(Inventory inventory) {
        if (inventory == null) return Optional.empty();
        var holder = inventory.getHolder(false);
        if (holder instanceof org.bukkit.entity.Entity) return Optional.empty();
        if (holder instanceof org.bukkit.block.DoubleChest doubleChest) {
            holder = doubleChest.getLeftSide();
        }
        if (holder instanceof BlockState block) {
            return Optional.of(new PaperLoadedTrackedContainerIndex.BlockKey(
                    block.getWorld().getUID(), block.getX(), block.getY(), block.getZ()));
        }
        var location = inventory.getLocation();
        if (location == null || location.getWorld() == null) return Optional.empty();
        return Optional.of(new PaperLoadedTrackedContainerIndex.BlockKey(
                location.getWorld().getUID(), location.getBlockX(),
                location.getBlockY(), location.getBlockZ()));
    }

    boolean scanTracked(
            Plugin plugin, PaperLoadedTrackedContainerIndex.ContainerKey key,
            long tick) {
        World world = plugin.getServer().getWorld(key.first().worldId());
        if (world == null || !allChunksLoaded(world, key)) return false;
        var block = key.first();
        BlockState state = world.getBlockAt(block.x(), block.y(), block.z()).getState(false);
        Optional<PaperLoadedTrackedContainerIndex.ContainerKey> actual = containerKey(state);
        if (actual.isEmpty() || !actual.orElseThrow().equals(key)) {
            index.blockChanged(block);
            scanBlock(plugin, block, tick);
            return false;
        }
        prepareTick(tick);
        return scanContainer(state, TrackingObservationUseCase.Presence.PRESENT,
                "tracked-container-recheck", new PaperScanLimit(MAX_ITEMS_PER_SCAN), tick);
    }

    private boolean scanContainer(
            BlockState state, TrackingObservationUseCase.Presence presence,
            String source, PaperScanLimit limit, long tick) {
        Optional<ContainerRead> maybe = resolveContainer(state);
        if (maybe.isEmpty()) return !(state instanceof InventoryHolder);
        ContainerRead read = maybe.orElseThrow();
        var key = read.key();
        if (!scannedThisTick.add(key)) return true;
        if (key.second() != null && read.inventory().getSize() != 54) {
            return unresolved(key, tick);
        }
        Optional<PaperPhysicalInventorySnapshot> reference =
                PaperPhysicalInventorySnapshot.capture(read.inventory());
        if (reference.isEmpty() || !limit.tryConsume()) return unresolved(key, tick);
        return scanCaptured(read, reference.orElseThrow(), presence, source, limit, tick);
    }

    private Optional<ContainerRead> resolveContainer(BlockState state) {
        if (!(state instanceof InventoryHolder holder)) return Optional.empty();
        // A paired chest may only expose its combined inventory once both chunks load.
        if (state instanceof Chest && containerKey(state).isEmpty()) return Optional.empty();
        Inventory inventory = holder.getInventory();
        return containerKey(state, inventory).map(key -> new ContainerRead(key, inventory));
    }

    private boolean scanCaptured(
            ContainerRead read, PaperPhysicalInventorySnapshot reference,
            TrackingObservationUseCase.Presence presence, String source,
            PaperScanLimit limit, long tick) {
        Optional<ItemStack[]> contents = Optional.ofNullable(read.inventory().getContents());
        if (contents.isEmpty()) return unresolved(read.key(), tick);
        ItemStack[] items = contents.orElseThrow();
        if (items.length > MAX_ITEMS_PER_SCAN) {
            exhaust(limit);
            return unresolved(read.key(), tick);
        }
        scanner.scanInventory(read.inventory(), reference.type(), reference.key(), presence,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                source + "-container", limit);
        boolean complete = limit.hasRemaining();
        index.observed(read.key(), hasIdentityEvidence(items), complete, tick);
        if (!complete) metrics.increment("tracking.scan_container_unresolved");
        return complete;
    }

    private boolean unresolved(PaperLoadedTrackedContainerIndex.ContainerKey key, long tick) {
        index.observed(key, true, false, tick);
        metrics.increment("tracking.scan_container_unresolved");
        return false;
    }

    private static void exhaust(PaperScanLimit limit) {
        for (int n = 0; n <= MAX_ITEMS_PER_SCAN; n++) limit.tryConsume();
    }

    private record ContainerRead(
            PaperLoadedTrackedContainerIndex.ContainerKey key, Inventory inventory) { }

    private boolean hasIdentityEvidence(ItemStack[] items) {
        for (ItemStack item : items) {
            if (scanner.hasIdentityEvidence(item)) return true;
        }
        return false;
    }

    private void prepareTick(long tick) {
        if (lastTick == tick) return;
        lastTick = tick;
        scannedThisTick.clear();
    }

    private static boolean allChunksLoaded(
            World world, PaperLoadedTrackedContainerIndex.ContainerKey key) {
        for (var chunk : key.chunks()) {
            if (!world.isChunkLoaded(chunk.x(), chunk.z())) return false;
        }
        return true;
    }

    /** No neighbor inventory or block access until its chunk is already loaded. */
    static Optional<PaperLoadedTrackedContainerIndex.ContainerKey> containerKey(
            BlockState state) {
        return containerKey(state, null);
    }

    private static Optional<PaperLoadedTrackedContainerIndex.ContainerKey> containerKey(
            BlockState state, Inventory inventory) {
        if (!(state instanceof InventoryHolder)) return Optional.empty();
        org.bukkit.Location location = locationOf(state, inventory);
        World world = location.getWorld();
        if (world == null) return Optional.empty();
        var first = new PaperLoadedTrackedContainerIndex.BlockKey(
                world.getUID(), location.getBlockX(), location.getBlockY(),
                location.getBlockZ());
        if (!(state instanceof Chest)) {
            return Optional.of(PaperLoadedTrackedContainerIndex.ContainerKey.single(first));
        }
        return chestKey(state, world, first);
    }

    private static org.bukkit.Location locationOf(BlockState state, Inventory inventory) {
        if (inventory == null || state instanceof Chest) return state.getLocation();
        org.bukkit.Location location = inventory.getLocation();
        return location != null ? location : state.getLocation();
    }

    private static Optional<PaperLoadedTrackedContainerIndex.ContainerKey> chestKey(
            BlockState state, World world,
            PaperLoadedTrackedContainerIndex.BlockKey first) {
        if (!(state.getBlockData() instanceof org.bukkit.block.data.type.Chest chest)
                || chest.getType() == org.bukkit.block.data.type.Chest.Type.SINGLE) {
            return Optional.of(PaperLoadedTrackedContainerIndex.ContainerKey.single(first));
        }
        var neighbor = neighbor(first, chest);
        if (!world.isChunkLoaded(neighbor.x() >> 4, neighbor.z() >> 4)) {
            return Optional.empty();
        }
        BlockState other = world.getBlockAt(
                neighbor.x(), neighbor.y(), neighbor.z()).getState(false);
        return matchingHalf(state, chest, other)
                ? Optional.of(PaperLoadedTrackedContainerIndex.ContainerKey.paired(first, neighbor))
                : Optional.empty();
    }

    private static boolean matchingHalf(
            BlockState first, org.bukkit.block.data.type.Chest chest, BlockState other) {
        if (!(other instanceof Chest)
                || !(other.getBlockData() instanceof org.bukkit.block.data.type.Chest adjacent)) {
            return false;
        }
        return adjacent.getFacing() == chest.getFacing()
                && adjacent.getType() != chest.getType()
                && adjacent.getType() != org.bukkit.block.data.type.Chest.Type.SINGLE
                && other.getType() == first.getType();
    }

    private static PaperLoadedTrackedContainerIndex.BlockKey neighbor(
            PaperLoadedTrackedContainerIndex.BlockKey first,
            org.bukkit.block.data.type.Chest chest) {
        BlockFace facing = chest.getFacing();
        int sign = chest.getType() == org.bukkit.block.data.type.Chest.Type.LEFT ? 1 : -1;
        int dx = switch (facing) {
            case NORTH -> sign;
            case SOUTH -> -sign;
            default -> 0;
        };
        int dz = switch (facing) {
            case EAST -> sign;
            case WEST -> -sign;
            default -> 0;
        };
        return new PaperLoadedTrackedContainerIndex.BlockKey(
                first.worldId(), first.x() + dx, first.y(), first.z() + dz);
    }
}
