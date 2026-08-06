package net.enthusia.loreitems.paper;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.plugin.Plugin;

/** Bounded main-thread walker over containers in chunks that are already loaded. */
final class PaperLoadedContainerWalker {
    private static final Chunk[] EMPTY_CHUNKS = new Chunk[0];
    private static final BlockState[] EMPTY_STATES = new BlockState[0];

    private final Source source;
    private int worldCursor;
    private int chunkCursor;
    private int stateCursor;
    private UUID snapshottedWorldId;
    private Chunk[] chunks = EMPTY_CHUNKS;
    private BlockState[] states = EMPTY_STATES;

    PaperLoadedContainerWalker() {
        this(new BukkitSource());
    }

    PaperLoadedContainerWalker(Source source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    Step visitNext(Plugin plugin) {
        List<World> worlds = source.worlds(plugin);
        if (worlds.isEmpty() || worldCursor >= worlds.size()) {
            return Step.complete();
        }
        World world = worlds.get(worldCursor);
        if (!world.getUID().equals(snapshottedWorldId)) {
            snapshotWorld(world);
            return Step.transition();
        }
        while (stateCursor < states.length) {
            BlockState state = states[stateCursor++];
            if (state instanceof Container) {
                return Step.container(new PaperInventoryReference.Block(
                        world.getUID(), state.getX(), state.getY(), state.getZ()));
            }
        }
        if (chunkCursor < chunks.length) {
            snapshotChunk(chunks[chunkCursor++]);
            return Step.transition();
        }
        worldCursor++;
        resetWorldSnapshot();
        return worldCursor >= worlds.size() ? Step.complete() : Step.transition();
    }

    void clear() {
        worldCursor = 0;
        resetWorldSnapshot();
    }

    private void snapshotWorld(World world) {
        snapshottedWorldId = world.getUID();
        chunks = source.loadedChunks(world);
        chunkCursor = 0;
        states = EMPTY_STATES;
        stateCursor = 0;
    }

    private void snapshotChunk(Chunk chunk) {
        states = chunk.isLoaded() ? source.tileEntities(chunk) : EMPTY_STATES;
        stateCursor = 0;
    }

    private void resetWorldSnapshot() {
        snapshottedWorldId = null;
        chunks = EMPTY_CHUNKS;
        chunkCursor = 0;
        states = EMPTY_STATES;
        stateCursor = 0;
    }

    interface Source {
        List<World> worlds(Plugin plugin);

        Chunk[] loadedChunks(World world);

        BlockState[] tileEntities(Chunk chunk);
    }

    record Step(PaperInventoryReference.Block containerReference, boolean sweepComplete) {
        private static Step container(PaperInventoryReference.Block reference) {
            return new Step(Objects.requireNonNull(reference, "reference"), false);
        }

        private static Step transition() {
            return new Step(null, false);
        }

        private static Step complete() {
            return new Step(null, true);
        }
    }

    private static final class BukkitSource implements Source {
        @Override
        public List<World> worlds(Plugin plugin) {
            return plugin.getServer().getWorlds();
        }

        @Override
        public Chunk[] loadedChunks(World world) {
            return world.getLoadedChunks();
        }

        @Override
        public BlockState[] tileEntities(Chunk chunk) {
            return chunk.getTileEntities(false);
        }
    }
}
