package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class PaperEntityTemplateUpdateControllerTest {
    private static final LoreDefinitionId DEFINITION_ID = new LoreDefinitionId(
            UUID.fromString("11111111-1111-1111-1111-111111111111"));

    private ServerMock server;
    private Plugin plugin;
    private World world;
    private PaperTemplateUpdateAccessRegistry registry;
    private PaperEntityTemplateUpdateController controller;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        world = server.addSimpleWorld("world");
        registry = new PaperTemplateUpdateAccessRegistry();
    }

    @AfterEach
    void tearDown() {
        if (controller != null) {
            controller.close();
        }
        MockBukkit.unmock();
    }

    @Test
    void loadedSweepHonorsThePerTickEntityBudgetAndPublishesOnlyWhenComplete() {
        dropTracked(1);
        dropTracked(2);
        RecordingScanner scanner = new RecordingScanner();
        controller = new PaperEntityTemplateUpdateController(plugin, registry, 1, scanner);

        int previousScans = 0;
        for (int pass = 0; pass < 32; pass++) {
            controller.drain();
            assertTrue(scanner.scanCalls - previousScans <= 1);
            previousScans = scanner.scanCalls;
            PaperTemplateUpdateAccessRegistry.DispatchBatch batch =
                    registry.prepareDispatch(server.getOnlinePlayers());
            if (!batch.candidates().isEmpty()) {
                assertEquals(2, batch.candidates().size());
                registry.finishDispatch(batch, java.util.Set.of());
                return;
            }
            registry.finishDispatch(batch, java.util.Set.of());
        }
        throw new AssertionError("Loaded entity coverage did not complete within bounded steps");
    }

    @Test
    void naturalEventUpdatesAnAlreadyCompleteEntitySnapshot() {
        controller = new PaperEntityTemplateUpdateController(plugin, registry, 2);
        completeInitialSweep();
        Item item = dropTracked(3);

        controller.observe(item);

        PaperTemplateUpdateAccessRegistry.DispatchBatch batch =
                registry.prepareDispatch(server.getOnlinePlayers());
        assertEquals(1, batch.candidates().size());
        assertEquals(item.getUniqueId(), entityReference(batch.candidates().getFirst()).entityId());
        registry.finishDispatch(batch, java.util.Set.of());
    }

    @Test
    void chunkTopologyChangeFailsClosedUntilAReplacementSweepCompletes() {
        Item item = dropTracked(4);
        controller = new PaperEntityTemplateUpdateController(plugin, registry, 4);
        completeInitialSweep();
        PaperTemplateUpdateAccessRegistry.DispatchBatch initial =
                registry.prepareDispatch(server.getOnlinePlayers());
        assertEquals(1, initial.candidates().size());
        registry.finishDispatch(initial, java.util.Set.of());

        controller.topologyChanged();
        controller.observe(item);
        PaperTemplateUpdateAccessRegistry.DispatchBatch blocked =
                registry.prepareDispatch(server.getOnlinePlayers());
        assertTrue(blocked.candidates().isEmpty());
        registry.finishDispatch(blocked, java.util.Set.of());

        completeInitialSweep();
        PaperTemplateUpdateAccessRegistry.DispatchBatch restored =
                registry.prepareDispatch(server.getOnlinePlayers());
        assertEquals(1, restored.candidates().size());
        registry.finishDispatch(restored, java.util.Set.of());
    }

    private void completeInitialSweep() {
        for (int pass = 0; pass < 32 && !registry.entityCoverageComplete(); pass++) {
            controller.drain();
        }
        assertTrue(registry.entityCoverageComplete());
    }

    private Item dropTracked(int index) {
        LoreItemIdentity identity = new LoreItemIdentity(
                DEFINITION_ID,
                new LoreInstanceId(new UUID(0L, index)),
                new TemplateRevision(1));
        ItemStack item = new PaperItemIdentityCodec().writeIdentity(
                ItemStack.of(Material.NETHER_STAR), identity);
        return world.dropItem(new Location(world, index, 64, index), item);
    }

    private static PaperEntityTemplateUpdateReference entityReference(
            PaperTemplateUpdateScanner.Candidate candidate) {
        return (PaperEntityTemplateUpdateReference) candidate.reference();
    }

    private static final class RecordingScanner extends PaperEntityTemplateUpdateScanner {
        private final PaperEntityTemplateUpdateScanner delegate =
                new PaperEntityTemplateUpdateScanner();
        private int scanCalls;

        @Override
        PaperTemplateUpdateScanner.Candidate scan(Entity entity) {
            scanCalls++;
            return delegate.scan(entity);
        }
    }
}
