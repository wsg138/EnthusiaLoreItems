package net.enthusia.loreitems.paper;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.data.type.Chest;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Validates a double chest against both currently loaded blocks. All returned
 * references contain coordinates and UUIDs only, never a live Bukkit object.
 */
final class PaperDoubleChestIdentity {
    private static final int DOUBLE_SIZE = 54;
    private static final int HALF_SIZE = 27;

    private PaperDoubleChestIdentity() {}

    static Optional<PaperInventoryReference> capture(Inventory inventory, InventoryHolder holder) {
        if (inventory instanceof DoubleChestInventory doubleInventory) {
            return captureDouble(doubleInventory, holder);
        }
        if (holder instanceof DoubleChest) {
            return Optional.empty();
        }
        Location location = inventory.getLocation();
        if (location == null || location.getWorld() == null) {
            return Optional.empty();
        }
        if (holder instanceof org.bukkit.block.Chest chest) {
            return captureHalf(inventory, location, chest);
        }
        return captureOther(inventory, holder, location);
    }

    private static Optional<PaperInventoryReference> captureOther(
            Inventory inventory, InventoryHolder holder, Location location) {
        if (holder == null && (inventory.getSize() == HALF_SIZE
                || inventory.getSize() == DOUBLE_SIZE)) {
            return Optional.empty();
        }
        return Optional.of(new PaperInventoryReference.Block(
                location.getWorld().getUID(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ()));
    }

    private static Optional<PaperInventoryReference> captureDouble(
            DoubleChestInventory inventory, InventoryHolder holder) {
        if (inventory.getSize() != DOUBLE_SIZE) {
            return Optional.empty();
        }
        Location left = sideLocation(inventory.getLeftSide(), holder, true);
        Location right = sideLocation(inventory.getRightSide(), holder, false);
        Optional<PaperInventoryReference.DoubleChestBlock> pair = pair(left, right);
        return pair.filter(ref -> resolve(left.getWorld(), ref).isPresent())
                .map(ref -> ref);
    }

    private static Optional<PaperInventoryReference> captureHalf(
            Inventory inventory, Location location, org.bukkit.block.Chest holder) {
        World world = location.getWorld();
        Position origin = Position.at(location);
        if (!(holder.getBlockData() instanceof Chest data)
                || inventory.getSize() != HALF_SIZE) {
            return Optional.empty();
        }
        return data.getType() == Chest.Type.SINGLE
                ? captureSingle(world, origin)
                : capturePairedHalf(world, origin, data);
    }

    private static Optional<PaperInventoryReference> captureSingle(
            World world, Position origin) {
        if (!loaded(world, origin)) {
            return Optional.empty();
        }
        BlockState state = state(world, origin);
        if (!(state instanceof org.bukkit.block.Chest)
                || !(state.getBlockData() instanceof Chest current)
                || current.getType() != Chest.Type.SINGLE) {
            return Optional.empty();
        }
        return Optional.of(new PaperInventoryReference.Block(
                world.getUID(), origin.x(), origin.y(), origin.z()));
    }

    private static Optional<PaperInventoryReference> capturePairedHalf(
            World world, Position origin, Chest data) {
        Position neighbor = partner(origin, data);
        if (neighbor == null || !loaded(world, origin) || !loaded(world, neighbor)) {
            return Optional.empty();
        }
        Position leftSide = data.getType() == Chest.Type.LEFT ? origin : neighbor;
        PaperInventoryReference.DoubleChestBlock ref =
                sortedPair(world.getUID(), origin, neighbor, leftSide);
        return resolve(world, ref).map(ignored -> ref);
    }

    static Optional<Inventory> resolve(
            World world, PaperInventoryReference.DoubleChestBlock pair) {
        if (world == null || !world.getUID().equals(pair.worldId())
                || !loaded(world, pair.first()) || !loaded(world, pair.second())) {
            return Optional.empty();
        }
        BlockState first = state(world, pair.first());
        BlockState second = state(world, pair.second());
        if (!validPair(first, second, pair.first(), pair.second())) {
            return Optional.empty();
        }
        Inventory combined = ((org.bukkit.block.Chest) first).getInventory();
        if (!(combined instanceof DoubleChestInventory doubleInventory)
                || combined.getSize() != DOUBLE_SIZE) {
            return Optional.empty();
        }
        InventoryHolder holder = doubleInventory.getHolder(false);
        Location left = sideLocation(doubleInventory.getLeftSide(), holder, true);
        Location right = sideLocation(doubleInventory.getRightSide(), holder, false);
        Position physicalLeft = leftForPair(first, second, pair);
        return pair(left, right)
                .filter(pair::equals)
                .filter(ref -> ref.leftSide().equals(physicalLeft))
                .map(ignored -> combined);
    }

    private static Position leftForPair(
            BlockState first, BlockState second,
            PaperInventoryReference.DoubleChestBlock pair) {
        Chest firstData = (Chest) first.getBlockData();
        return firstData.getType() == Chest.Type.LEFT ? pair.first() : pair.second();
    }

    private static boolean validPair(
            BlockState first, BlockState second, Position a, Position b) {
        if (!(first instanceof org.bukkit.block.Chest)
                || !(second instanceof org.bukkit.block.Chest)
                || first.getType() != second.getType()) {
            return false;
        }
        return validData(first.getBlockData(), second.getBlockData(), a, b);
    }

    private static boolean validData(
            org.bukkit.block.data.BlockData first, org.bukkit.block.data.BlockData second,
            Position a, Position b) {
        if (!(first instanceof Chest left) || !(second instanceof Chest right)) {
            return false;
        }
        return left.getType() != Chest.Type.SINGLE
                && right.getType() != Chest.Type.SINGLE
                && left.getFacing() == right.getFacing()
                && b.equals(partner(a, left))
                && a.equals(partner(b, right));
    }

    private static BlockState state(World world, Position position) {
        return world.getBlockAt(position.x(), position.y(), position.z())
                .getState(false);
    }

    private static Position partner(Position position, Chest data) {
        if (data.getType() == Chest.Type.SINGLE) {
            return null;
        }
        int sign = data.getType() == Chest.Type.LEFT ? 1 : -1;
        int dx = -data.getFacing().getModZ() * sign;
        int dz = data.getFacing().getModX() * sign;
        return Math.abs(dx) + Math.abs(dz) == 1
                ? new Position(position.x() + dx, position.y(), position.z() + dz)
                : null;
    }

    private static boolean loaded(World world, Position position) {
        return world.isChunkLoaded(position.x() >> 4, position.z() >> 4);
    }

    private static Location sideLocation(
            Inventory inventory, InventoryHolder holder, boolean left) {
        Location location = inventory == null ? null : inventory.getLocation();
        if (location != null || !(holder instanceof DoubleChest chest)) {
            return location;
        }
        InventoryHolder side = left ? chest.getLeftSide(false) : chest.getRightSide(false);
        return side instanceof BlockState state ? state.getLocation() : null;
    }

    private static Optional<PaperInventoryReference.DoubleChestBlock> pair(
            Location left, Location right) {
        if (left == null || right == null || left.getWorld() == null
                || right.getWorld() == null
                || !left.getWorld().getUID().equals(right.getWorld().getUID())) {
            return Optional.empty();
        }
        Position a = Position.at(left);
        Position b = Position.at(right);
        return a.adjacent(b)
                ? Optional.of(sortedPair(left.getWorld().getUID(), a, b, a))
                : Optional.empty();
    }

    private static PaperInventoryReference.DoubleChestBlock sortedPair(
            UUID worldId, Position a, Position b, Position leftSide) {
        return a.compareTo(b) < 0
                ? new PaperInventoryReference.DoubleChestBlock(worldId, a, b, leftSide)
                : new PaperInventoryReference.DoubleChestBlock(worldId, b, a, leftSide);
    }

    static String key(PaperInventoryReference.DoubleChestBlock ref) {
        return "double-chest:" + ref.worldId() + ':' + ref.first().key()
                + '|' + ref.second().key();
    }

    record Position(int x, int y, int z) implements Comparable<Position> {
        static Position at(Location location) {
            return new Position(location.getBlockX(), location.getBlockY(),
                    location.getBlockZ());
        }

        String key() {
            return x + ":" + y + ":" + z;
        }

        boolean adjacent(Position other) {
            return y == other.y && Math.abs(x - other.x) + Math.abs(z - other.z) == 1;
        }

        @Override
        public int compareTo(Position other) {
            int xOrder = Integer.compare(x, other.x);
            if (xOrder != 0) {
                return xOrder;
            }
            int yOrder = Integer.compare(y, other.y);
            return yOrder != 0 ? yOrder : Integer.compare(z, other.z);
        }
    }
}
