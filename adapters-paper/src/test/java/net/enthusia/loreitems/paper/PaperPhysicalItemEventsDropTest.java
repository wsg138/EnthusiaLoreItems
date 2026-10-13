package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import net.enthusia.loreitems.domain.LocationDescriptor;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Item;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

/** Root drop is authoritative; nested shulker contents never inherit that proof. */
class PaperPhysicalItemEventsDropTest {
    private static final LoreItemIdentity IDENTITY = new LoreItemIdentity(
            new LoreDefinitionId(UUID.fromString("11111111-1111-1111-1111-111111111111")),
            new LoreInstanceId(UUID.fromString("22222222-2222-2222-2222-222222222222")),
            new TemplateRevision(1));

    private ServerMock server;
    private PluginMock plugin;
    private PaperTrackingCoordinator coordinator;
    private PaperDeferredMainThreadActions deferred;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        deferred = new PaperDeferredMainThreadActions(plugin, "test");
    }

    @AfterEach
    void tearDown() {
        deferred.close();
        if (coordinator != null) coordinator.close();
        MockBukkit.unmock();
    }

    @Test
    void realPlayerDropEmitsAuthoritativeRootBeforeConservativeTree() {
        List<TrackingObservationUseCase.Request> observed = new CopyOnWriteArrayList<>();
        PaperPhysicalItemEvents events = events(observed);
        World world = server.addSimpleWorld("world");
        Item dropped = world.dropItem(new Location(world, 4, 64, 7), tracked());

        events.onDrop(new PlayerDropItemEvent(server.addPlayer(), dropped));

        assertEquals(2, observed.size());
        assertEquals(TrackingObservationUseCase.EvidenceMode.AUTHORITATIVE_TRANSITION,
                observed.getFirst().mode());
        assertEquals("player-drop", observed.getFirst().source());
        assertEquals(LocationDescriptor.Type.DROPPED_ITEM,
                observed.getFirst().location().type());
        assertEquals(IDENTITY, observed.getFirst().identity());
        assertEquals(TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                observed.get(1).mode());
        assertEquals("player-drop-tree", observed.get(1).source());
    }

    @Test
    void dropOfUntrackedShulkerKeepsNestedTrackedCopyConservative() {
        List<TrackingObservationUseCase.Request> observed = new CopyOnWriteArrayList<>();
        PaperPhysicalItemEvents events = events(observed);
        World world = server.addSimpleWorld("world");
        Item dropped = world.dropItem(new Location(world, 4, 64, 7),
                shulkerContaining(tracked()));

        events.onDrop(new PlayerDropItemEvent(server.addPlayer(), dropped));

        assertEquals(1, observed.size());
        assertEquals(TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                observed.getFirst().mode());
        assertEquals(LocationDescriptor.Type.NESTED_CONTAINER,
                observed.getFirst().location().type());
        assertEquals("player-drop-tree", observed.getFirst().source());
        assertTrue(observed.getFirst().location().containerPath().contains("/shulker:0"));
    }

    private PaperPhysicalItemEvents events(
            List<TrackingObservationUseCase.Request> observed) {
        TrackingObservationUseCase useCase = request -> {
            observed.add(request);
            return CompletableFuture.completedFuture(TrackingObservationUseCase.Result.of(
                    TrackingObservationUseCase.Status.RECORDED, "recorded"));
        };
        coordinator = new PaperTrackingCoordinator(
                plugin, () -> useCase, () -> 4, MetricsPort.noOp());
        return new PaperPhysicalItemEvents(
                plugin, new PaperPhysicalInventoryScanner(coordinator),
                deferred, (id, source) -> {});
    }

    private static ItemStack tracked() {
        return new PaperItemIdentityCodec().writeIdentity(
                ItemStack.of(Material.DIAMOND_SWORD), IDENTITY);
    }

    private static ItemStack shulkerContaining(ItemStack inside) {
        ItemStack outer = ItemStack.of(Material.SHULKER_BOX);
        BlockStateMeta meta = assertInstanceOf(BlockStateMeta.class, outer.getItemMeta());
        ShulkerBox box = assertInstanceOf(ShulkerBox.class, meta.getBlockState());
        box.getInventory().setItem(0, inside);
        meta.setBlockState(box);
        assertTrue(outer.setItemMeta(meta));
        return outer;
    }
}
