package net.enthusia.loreitems.paper;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/** Bounded event and loaded-entity discovery for naturally encountered template updates. */
final class PaperEntityTemplateUpdateController implements AutoCloseable {
    private static final int MIN_BUDGET = 1;
    private static final int RESCAN_PERIOD_TICKS = 100;
    private static final Entity[] EMPTY_ENTITIES = new Entity[0];
    private static final Chunk[] EMPTY_CHUNKS = new Chunk[0];

    private final Plugin plugin;
    private final PaperTemplateUpdateAccessRegistry registry;
    private final PaperEntityTemplateUpdateScanner scanner;
    private final int budget;
    private final LoadedEntityWalker loadedWalker = new LoadedEntityWalker();
    private final Set<UUID> seenEntityIds = new HashSet<>();

    private int ticksUntilSweep;
    private boolean sweeping;
    private boolean rescanRequired;
    private boolean closed;

    PaperEntityTemplateUpdateController(
            Plugin plugin,
            PaperTemplateUpdateAccessRegistry registry,
            int budget) {
        this(plugin, registry, budget, new PaperEntityTemplateUpdateScanner());
    }

    PaperEntityTemplateUpdateController(
            Plugin plugin,
            PaperTemplateUpdateAccessRegistry registry,
            int budget,
            PaperEntityTemplateUpdateScanner scanner) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.scanner = Objects.requireNonNull(scanner, "scanner");
        if (budget < MIN_BUDGET) {
            throw new IllegalArgumentException("budget must be positive");
        }
        this.budget = budget;
        beginSweep();
    }

    void observe(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        if (!PaperEntityTemplateUpdateReference.supports(entity)) {
            return;
        }
        UUID entityId = entity.getUniqueId();
        List<PaperTemplateUpdateScanner.Candidate> candidates = scanner.scanAll(entity);
        if (candidates.isEmpty()) {
            registry.removeEntity(entityId);
        } else {
            registry.replaceEntity(entityId, candidates);
        }
        if (sweeping) {
            seenEntityIds.add(entityId);
        }
    }

    void remove(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        if (!PaperEntityTemplateUpdateReference.supports(entity)) {
            return;
        }
        UUID entityId = entity.getUniqueId();
        registry.removeEntity(entityId);
        seenEntityIds.remove(entityId);
    }

    void scheduleNextTick(Entity entity) {
        Objects.requireNonNull(entity, "entity");
        UUID entityId = entity.getUniqueId();
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                Entity current = plugin.getServer().getEntity(entityId);
                if (current == null) {
                    registry.removeEntity(entityId);
                } else {
                    observe(current);
                }
            });
        } catch (RuntimeException exception) {
            plugin.getLogger().log(
                    Level.FINE,
                    "Could not schedule natural entity template-update discovery during shutdown.",
                    exception);
        }
    }

    void topologyChanged() {
        if (closed) {
            return;
        }
        registry.markEntityCoverageIncomplete();
        if (sweeping) {
            rescanRequired = true;
        } else {
            beginSweep();
        }
    }

    void drain() {
        if (closed) {
            return;
        }
        if (!sweeping) {
            if (ticksUntilSweep > 0) {
                ticksUntilSweep--;
                return;
            }
            beginSweep();
        }
        for (int count = 0; count < budget; count++) {
            LoadedEntityWalker.Step step = loadedWalker.visitNext(plugin);
            if (step.entity() != null) {
                observe(step.entity());
            }
            if (step.sweepComplete()) {
                finishSweep();
                return;
            }
        }
    }

    private void beginSweep() {
        registry.markEntityCoverageIncomplete();
        loadedWalker.clear();
        seenEntityIds.clear();
        sweeping = true;
        rescanRequired = false;
        ticksUntilSweep = 0;
    }

    private void finishSweep() {
        if (rescanRequired) {
            beginSweep();
            return;
        }
        registry.completeEntityCoverage(seenEntityIds);
        sweeping = false;
        ticksUntilSweep = RESCAN_PERIOD_TICKS;
        loadedWalker.clear();
        seenEntityIds.clear();
    }

    @Override
    public void close() {
        closed = true;
        loadedWalker.clear();
        seenEntityIds.clear();
    }

    /** One invocation performs at most one world/chunk transition or returns one entity. */
    private static final class LoadedEntityWalker {
        private int worldCursor;
        private int chunkCursor;
        private int entityCursor;
        private UUID snapshottedWorldId;
        private Chunk[] chunks = EMPTY_CHUNKS;
        private Entity[] entities = EMPTY_ENTITIES;

        private Step visitNext(Plugin plugin) {
            List<World> worlds = plugin.getServer().getWorlds();
            if (worlds.isEmpty() || worldCursor >= worlds.size()) {
                return Step.complete();
            }
            World world = worlds.get(worldCursor);
            if (!world.getUID().equals(snapshottedWorldId)) {
                snapshotWorld(world);
                return Step.transition();
            }
            if (entityCursor < entities.length) {
                return Step.entity(entities[entityCursor++]);
            }
            if (chunkCursor < chunks.length) {
                snapshotChunk(chunks[chunkCursor++]);
                return Step.transition();
            }
            worldCursor++;
            resetWorldSnapshot();
            return worldCursor >= worlds.size() ? Step.complete() : Step.transition();
        }

        private void snapshotWorld(World world) {
            snapshottedWorldId = world.getUID();
            chunks = world.getLoadedChunks();
            chunkCursor = 0;
            entities = EMPTY_ENTITIES;
            entityCursor = 0;
        }

        private void snapshotChunk(Chunk chunk) {
            entities = chunk.isLoaded() ? chunk.getEntities() : EMPTY_ENTITIES;
            entityCursor = 0;
        }

        private void resetWorldSnapshot() {
            snapshottedWorldId = null;
            chunks = EMPTY_CHUNKS;
            chunkCursor = 0;
            entities = EMPTY_ENTITIES;
            entityCursor = 0;
        }

        private void clear() {
            worldCursor = 0;
            resetWorldSnapshot();
        }

        private record Step(Entity entity, boolean sweepComplete) {
            private static Step entity(Entity entity) {
                return new Step(Objects.requireNonNull(entity, "entity"), false);
            }

            private static Step transition() {
                return new Step(null, false);
            }

            private static Step complete() {
                return new Step(null, true);
            }
        }
    }
}
