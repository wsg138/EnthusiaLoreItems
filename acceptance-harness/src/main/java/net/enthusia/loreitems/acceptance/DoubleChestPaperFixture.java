package net.enthusia.loreitems.acceptance;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Test-only, fixed-coordinate double-chest fixture for an isolated Paper world.
 *
 * <p>This class deliberately does not construct tracked item identities, force-load chunks,
 * run shell commands, change plugins, or claim LoreItems tracking correctness. It only
 * proves the actual Paper chest geometry that later movement tests depend on.
 */
final class DoubleChestPaperFixture {
    private static final Map<String, Pair> CASES = Map.of(
            "same", new Pair(8, 8, 9, 8, BlockFace.NORTH),
            "xplus", new Pair(15, 8, 16, 8, BlockFace.NORTH),
            "zplus", new Pair(8, 15, 8, 16, BlockFace.EAST),
            "xminus", new Pair(-1, 8, 0, 8, BlockFace.NORTH),
            "zminus", new Pair(8, -1, 8, 0, BlockFace.EAST));

    private DoubleChestPaperFixture() {}

    static void command(CommandSender sender, String[] arguments) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(arguments, "arguments");
        if (arguments.length != 3) {
            throw new IllegalArgumentException(
                    "doublechest <setup|inspect> <same|xplus|zplus|xminus|zminus>");
        }
        String action = arguments[1].toLowerCase(Locale.ROOT);
        Pair pair = caseFor(arguments[2]);
        World world = Objects.requireNonNull(Bukkit.getWorld("world"), "isolated test world");
        if (!pair.loaded(world)) {
            sender.sendMessage("WP05_DOUBLE_CHEST_UNRESOLVED case=" + arguments[2]
                    + " reason=HALF_CHUNK_NOT_LOADED");
            return;
        }
        if ("setup".equals(action)) {
            setup(world, pair);
        } else if (!"inspect".equals(action)) {
            throw new IllegalArgumentException("doublechest action must be setup or inspect");
        }
        inspect(world, pair, sender, arguments[2]);
    }

    static Pair caseFor(String name) {
        Pair pair = CASES.get(name.toLowerCase(Locale.ROOT));
        if (pair == null) {
            throw new IllegalArgumentException("unknown doublechest case " + name);
        }
        return pair;
    }

    private static void setup(World world, Pair pair) {
        // Do not call getBlockAt until both naturally loaded chunks have been verified.
        Block first = world.getBlockAt(pair.x1, 70, pair.z1);
        Block second = world.getBlockAt(pair.x2, 70, pair.z2);
        if (!isSafeFixtureBlock(first) || !isSafeFixtureBlock(second)) {
            throw new IllegalStateException("doublechest fixture would replace non-chest blocks");
        }
        first.setType(Material.CHEST, false);
        second.setType(Material.CHEST, false);
        configure(first, pair.facing, org.bukkit.block.data.type.Chest.Type.LEFT);
        configure(second, pair.facing, org.bukkit.block.data.type.Chest.Type.RIGHT);
        if (!(first.getState(false) instanceof Chest firstChest)
                || !(firstChest.getInventory() instanceof DoubleChestInventory combined)) {
            throw new IllegalStateException("fixture chest halves did not join on real Paper");
        }
        Inventory left = combined.getLeftSide();
        Inventory right = combined.getRightSide();
        if (left == null || right == null || left.getSize() != 27
                || right.getSize() != 27 || combined.getSize() != 54) {
            throw new IllegalStateException("invalid test chest half inventory topology");
        }
        // Nontracked unique markers give a meaningful physical 26->27 boundary
        // check. These never carry LoreItems PDC data or simulate item movement.
        left.setItem(0, new ItemStack(Material.COBBLESTONE));
        left.setItem(26, new ItemStack(Material.GRANITE));
        right.setItem(0, new ItemStack(Material.DIORITE));
        right.setItem(26, new ItemStack(Material.ANDESITE));
    }

    private static boolean isSafeFixtureBlock(Block block) {
        return block.getType().isAir();
    }

    private static void configure(
            Block block, BlockFace facing, org.bukkit.block.data.type.Chest.Type type) {
        org.bukkit.block.data.type.Chest data =
                (org.bukkit.block.data.type.Chest) block.getBlockData();
        data.setFacing(facing);
        data.setType(type);
        block.setBlockData(data, false);
    }

    private static void inspect(
            World world, Pair pair, CommandSender sender, String caseName) {
        if (!pair.loaded(world)) {
            sender.sendMessage("WP05_DOUBLE_CHEST_UNRESOLVED case=" + caseName
                    + " reason=HALF_UNLOADED_DURING_INSPECT");
            return;
        }
        DoubleChestInventory combined = resolvedInventory(world, pair);
        verifyHalfMapping(combined, world, pair);
        sender.sendMessage("WP05_DOUBLE_CHEST_TOPOLOGY_OK case=" + caseName
                + " world_uuid=" + world.getUID()
                + " first=" + pair.x1 + ",70," + pair.z1
                + " second=" + pair.x2 + ",70," + pair.z2
                + " chunks=" + pair.chunkX1() + "," + pair.chunkZ1()
                + ";" + pair.chunkX2() + "," + pair.chunkZ2()
                + " slots=54 half_slots=27,27");
    }

    private static DoubleChestInventory resolvedInventory(World world, Pair pair) {
        Block firstBlock = world.getBlockAt(pair.x1, 70, pair.z1);
        Block secondBlock = world.getBlockAt(pair.x2, 70, pair.z2);
        if (!(firstBlock.getState(false) instanceof Chest first)
                || !(secondBlock.getState(false) instanceof Chest second)) {
            throw new IllegalStateException("doublechest requires two actual Chest block states");
        }
        if (!(first.getInventory() instanceof DoubleChestInventory combined)
                || second.getInventory().getSize() != 54) {
            throw new IllegalStateException("the pair does not expose a real 54-slot double chest");
        }
        return combined;
    }

    private static void verifyHalfMapping(
            DoubleChestInventory combined, World world, Pair pair) {
        Inventory left = combined.getLeftSide();
        Inventory right = combined.getRightSide();
        if (left == null || right == null || combined.getSize() != 54) {
            throw new IllegalStateException("double chest has no canonical 54-slot halves");
        }
        if (left.getSize() != 27 || right.getSize() != 27) {
            throw new IllegalStateException("the left/right inventories are not 27 slots each");
        }
        Chest first = (Chest) world.getBlockAt(pair.x1, 70, pair.z1).getState(false);
        Chest second = (Chest) world.getBlockAt(pair.x2, 70, pair.z2).getState(false);
        if (first.getBlockInventory().getSize() != 27
                || second.getBlockInventory().getSize() != 27) {
            throw new IllegalStateException("physical block half slots are not 27 each");
        }
        // Checking the composite against getLeftSide/getRightSide alone is not enough:
        // it can pass even if an inventory half was attributed to the wrong block.
        verifyPhysicalHalf(first, left, org.bukkit.block.data.type.Chest.Type.LEFT);
        verifyPhysicalHalf(second, right, org.bukkit.block.data.type.Chest.Type.RIGHT);
        verifyBoundaryItems(combined, left, right);
    }

    private static void verifyPhysicalHalf(
            Chest physical, Inventory side, org.bukkit.block.data.type.Chest.Type expectedType) {
        if (!(physical.getBlockData() instanceof org.bukkit.block.data.type.Chest data)
                || data.getType() != expectedType) {
            throw new IllegalStateException("physical chest left/right blockdata mismatch");
        }
        org.bukkit.Location block = physical.getLocation();
        org.bukkit.Location inventory = side.getLocation();
        if (block == null || block.getWorld() == null || inventory == null
                || inventory.getWorld() == null
                || !block.getWorld().getUID().equals(inventory.getWorld().getUID())
                || block.getBlockX() != inventory.getBlockX()
                || block.getBlockY() != inventory.getBlockY()
                || block.getBlockZ() != inventory.getBlockZ()) {
            throw new IllegalStateException("chest half inventory assigned to wrong physical block");
        }
        Inventory actualHalf = physical.getBlockInventory();
        if (!Objects.equals(actualHalf.getItem(0), side.getItem(0))
                || !Objects.equals(actualHalf.getItem(26), side.getItem(26))) {
            throw new IllegalStateException("physical chest block and half slots disagree");
        }
    }

    private static void verifyBoundaryItems(
            DoubleChestInventory combined, Inventory left, Inventory right) {
        requireMaterial(left.getItem(0), Material.COBBLESTONE);
        requireMaterial(left.getItem(26), Material.GRANITE);
        requireMaterial(right.getItem(0), Material.DIORITE);
        requireMaterial(right.getItem(26), Material.ANDESITE);
        if (!Objects.equals(combined.getItem(0), left.getItem(0))
                || !Objects.equals(combined.getItem(26), left.getItem(26))
                || !Objects.equals(combined.getItem(27), right.getItem(0))
                || !Objects.equals(combined.getItem(53), right.getItem(26))) {
            throw new IllegalStateException("double chest composite/half slot mapping disagrees");
        }
    }

    private static void requireMaterial(ItemStack actual, Material expected) {
        if (actual == null || actual.getType() != expected) {
            throw new IllegalStateException("double chest boundary marker missing or wrong: " + expected);
        }
    }

    record Pair(int x1, int z1, int x2, int z2, BlockFace facing) {
        int chunkX1() { return Math.floorDiv(x1, 16); }
        int chunkZ1() { return Math.floorDiv(z1, 16); }
        int chunkX2() { return Math.floorDiv(x2, 16); }
        int chunkZ2() { return Math.floorDiv(z2, 16); }

        boolean loaded(World world) {
            return world.isChunkLoaded(chunkX1(), chunkZ1())
                    && world.isChunkLoaded(chunkX2(), chunkZ2());
        }
    }
}
