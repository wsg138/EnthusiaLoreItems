package net.enthusia.loreitems.paper;

import java.util.Objects;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Chest;
import org.bukkit.block.BlockFace;

/** No-force-load check before a block reference resolves a composite chest inventory. */
final class PaperDoubleChestAvailabilityGuard {
    private PaperDoubleChestAvailabilityGuard() {}

    static boolean isAccessible(World world, Block original) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(original, "original");
        BlockData blockData = original.getBlockData();
        if (!(blockData instanceof Chest chest) || chest.getType() == Chest.Type.SINGLE) {
            return true;
        }
        Half partner = partner(
                original.getX(), original.getZ(), chest.getFacing(), chest.getType());
        if (partner == null || !world.isChunkLoaded(partner.x() >> 4, partner.z() >> 4)) {
            return false;
        }
        // Only now may the other half's block data be accessed; querying it earlier can
        // force chunk generation/loading on implementations with an unloaded neighbor.
        Block other = world.getBlockAt(partner.x(), original.getY(), partner.z());
        if (other.getType() != original.getType()
                || !(other.getBlockData() instanceof Chest otherChest)) {
            return false;
        }
        return otherChest.getFacing() == chest.getFacing()
                && otherChest.getType() == opposite(chest.getType());
    }

    static Half partner(int x, int z, BlockFace facing, Chest.Type half) {
        if (half == Chest.Type.SINGLE || facing.getModY() != 0
                || Math.abs(facing.getModX()) + Math.abs(facing.getModZ()) != 1) {
            return null;
        }
        int sign = half == Chest.Type.LEFT ? 1 : -1;
        return new Half(x - facing.getModZ() * sign, z + facing.getModX() * sign);
    }

    private static Chest.Type opposite(Chest.Type half) {
        return half == Chest.Type.LEFT ? Chest.Type.RIGHT : Chest.Type.LEFT;
    }

    record Half(int x, int z) {}
}
