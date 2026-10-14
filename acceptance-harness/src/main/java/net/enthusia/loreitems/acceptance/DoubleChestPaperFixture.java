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
            "zminus", new Pair(8, -1, 8, 0, BlockFace.EAST),
            "xminus2", new Pair(-17, 8, -16, 8, BlockFace.NORTH),
            "zminus2", new Pair(8, -17, 8, -16, BlockFace.EAST),
            "xremote", new Pair(2047, 8, 2048, 8, BlockFace.NORTH));

    private DoubleChestPaperFixture() {}

    static void command(CommandSender sender, String[] arguments) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(arguments, "arguments");
        if (arguments.length < 3 || arguments.length > 4) {
            throw new IllegalArgumentException(
                    "doublechest <setup|inspect> <case-name> or evict xremote <first|second>");
        }
        String action = arguments[1].toLowerCase(Locale.ROOT);
        Pair pair = caseFor(arguments[2]);
        World world = Objects.requireNonNull(Bukkit.getWorld("world"), "isolated test world");
        if ("evict".equals(action) || "probe".equals(action)) {
            if (arguments.length != 4 || !"xremote".equals(arguments[2])) {
                throw new IllegalArgumentException("evict/probe only allowed on disposable xremote");
            }
            remoteChunkCheck(world, pair, arguments[3], action, sender);
            return;
        }
        if (arguments.length != 3) {
            throw new IllegalArgumentException("setup/inspect needs exactly one case name");
        }
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

    /**
     * Fixture-only request for actual Paper chunk eviction after the TEST HOST
     * removes its force-load ticket. An unload is not simulated by a Boolean.
     * Do not read the remote block or double-chest inventory during eviction.
     */
    private static void remoteChunkCheck(
            World world, Pair pair, String half, String action, CommandSender sender) {
        if (!"first".equals(half) && !"second".equals(half)
                && !"control".equals(half)) {
            throw new IllegalArgumentException("remote half must be first, second or control");
        }
        // The control chunk is otherwise empty, remote from the test chest and spawn.
        // It distinguishes an inability to unload *any* chunk from chest-specific
        // tickets/neighbor access. Coordinates are chunk units, never read blocks.
        int x = "control".equals(half) ? 150
                : "first".equals(half) ? pair.chunkX1() : pair.chunkX2();
        int z = "control".equals(half) ? 0
                : "first".equals(half) ? pair.chunkZ1() : pair.chunkZ2();
        if ("probe".equals(action)) {
            sender.sendMessage("WP05_CHUNK_PROBE half=" + half
                    + " loaded=" + world.isChunkLoaded(x, z)
                    + " forced=" + world.isChunkForceLoaded(x, z)
                    + " plugin_tickets=" + world.getPluginChunkTickets(x, z).stream()
                            .map(plugin -> plugin.getName()).sorted().toList());
            return;
        }
        if (world.isChunkForceLoaded(x, z)) {
            sender.sendMessage("WP05_DOUBLE_CHEST_EVICT_BLOCKED half=" + half
                    + " reason=FORCELOAD_TICKET_ACTIVE");
            return;
        }
        if (!world.isChunkLoaded(x, z)) {
            sender.sendMessage("WP05_DOUBLE_CHEST_EVICT_OK half=" + half
                    + " reason=ALREADY_UNLOADED");
            return;
        }
        // Paper is allowed to delay unloading after a forceload ticket is
        // removed. Request a real server-side unload, then let the shell wait
        // for World.isChunkLoaded to actually turn false in ordinary inspect.
        boolean accepted = world.unloadChunkRequest(x, z);
        if (accepted) {
            sender.sendMessage("WP05_DOUBLE_CHEST_EVICT_REQUESTED half=" + half
                    + " reason=PAPER_UNLOAD_SCHEDULED");
        } else {
            sender.sendMessage("WP05_DOUBLE_CHEST_EVICT_FAILED half=" + half
                    + " reason=UNLOAD_REQUEST_REJECTED");
        }
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
        markBoundaryItems(joinedInventory(first));
    }

    private static DoubleChestInventory joinedInventory(Block first) {
        if (!(first.getState(false) instanceof Chest firstChest)
                || !(firstChest.getInventory() instanceof DoubleChestInventory combined)) {
            throw new IllegalStateException("fixture chest halves did not join on real Paper");
        }
        return combined;
    }

    private static void markBoundaryItems(DoubleChestInventory combined) {
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
        boolean firstIsPaperLeft = verifyHalfMapping(combined, world, pair);
        String productionKey = ProductionDoubleChestProbe.verify(
                world, pair, combined, firstIsPaperLeft);
        sender.sendMessage("WP05_DOUBLE_CHEST_PRODUCTION_OK case=" + caseName
                + " key=" + productionKey + " checked=combined,left,right,first,second");
        sender.sendMessage("WP05_DOUBLE_CHEST_TOPOLOGY_OK case=" + caseName
                + " world_uuid=" + world.getUID()
                + " first=" + pair.x1 + ",70," + pair.z1
                + " second=" + pair.x2 + ",70," + pair.z2
                + " chunks=" + pair.chunkX1() + "," + pair.chunkZ1()
                + ";" + pair.chunkX2() + "," + pair.chunkZ2()
                + " slots=54 half_slots=27,27"
                + " paper_left=" + (firstIsPaperLeft ? "first" : "second")
                + " first_block_type=LEFT second_block_type=RIGHT");
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

    private static boolean verifyHalfMapping(
            DoubleChestInventory combined, World world, Pair pair) {
        Inventory left = combined.getLeftSide();
        Inventory right = combined.getRightSide();
        requireCanonicalHalves(combined, left, right);
        Chest first = (Chest) world.getBlockAt(pair.x1, 70, pair.z1).getState(false);
        Chest second = (Chest) world.getBlockAt(pair.x2, 70, pair.z2).getState(false);
        requireHalfSize(first);
        requireHalfSize(second);
        // Bukkit Chest.Type and DoubleChestInventory left/right are independent
        // API concepts. Prove the physical block for EACH side from the real
        // Inventory.getLocation(), rather than assuming their names align.
        boolean firstIsLeft = sameBlock(first.getLocation(), left.getLocation());
        boolean secondIsLeft = sameBlock(second.getLocation(), left.getLocation());
        if (firstIsLeft == secondIsLeft) {
            throw new IllegalStateException("real Paper left inventory cannot be assigned to one block:"
                    + " first=" + coordinates(first.getLocation())
                    + " second=" + coordinates(second.getLocation())
                    + " left=" + coordinates(left.getLocation()));
        }
        Chest physicalLeft = firstIsLeft ? first : second;
        Chest physicalRight = firstIsLeft ? second : first;
        verifyPhysicalHalf(physicalLeft, left, "LEFT_API_HALF");
        verifyPhysicalHalf(physicalRight, right, "RIGHT_API_HALF");
        verifyBoundaryItems(combined, left, right);
        return firstIsLeft;
    }

    private static void requireCanonicalHalves(
            DoubleChestInventory combined, Inventory left, Inventory right) {
        if (left == null || right == null || combined.getSize() != 54) {
            throw new IllegalStateException("double chest has no canonical 54-slot halves");
        }
        if (left.getSize() != 27 || right.getSize() != 27) {
            throw new IllegalStateException("the left/right inventories are not 27 slots each");
        }
    }

    private static void requireHalfSize(Chest block) {
        if (block.getBlockInventory().getSize() != 27) {
            throw new IllegalStateException("physical block half slots are not 27 each");
        }
    }

    private static boolean sameBlock(org.bukkit.Location one, org.bukkit.Location two) {
        return one != null && two != null && one.getWorld() != null
                && two.getWorld() != null
                && one.getWorld().getUID().equals(two.getWorld().getUID())
                && one.getBlockX() == two.getBlockX()
                && one.getBlockY() == two.getBlockY()
                && one.getBlockZ() == two.getBlockZ();
    }

    private static void verifyPhysicalHalf(
            Chest physical, Inventory side, String sideName) {
        org.bukkit.Location block = physical.getLocation();
        org.bukkit.Location inventory = side.getLocation();
        if (!sameBlock(block, inventory)) {
            throw new IllegalStateException("chest half inventory assigned to wrong physical block:"
                    + " side=" + sideName
                    + " expected=" + coordinates(block)
                    + " inventory=" + coordinates(inventory));
        }
        Inventory actualHalf = physical.getBlockInventory();
        if (!Objects.equals(actualHalf.getItem(0), side.getItem(0))
                || !Objects.equals(actualHalf.getItem(26), side.getItem(26))) {
            throw new IllegalStateException("physical chest block and half slots disagree");
        }
    }

    private static String coordinates(org.bukkit.Location location) {
        if (location == null) {
            return "null";
        }
        return (location.getWorld() == null ? "world-null" : location.getWorld().getUID())
                + ":" + location.getBlockX() + ":" + location.getBlockY() + ":"
                + location.getBlockZ();
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
