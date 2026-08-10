package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Method;
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
import org.bukkit.entity.ArmorStand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

class PaperPhysicalEntityScannerTest {
    private static final LoreItemIdentity IDENTITY = new LoreItemIdentity(
            new LoreDefinitionId(UUID.fromString(
                    "11111111-1111-1111-1111-111111111111")),
            new LoreInstanceId(UUID.fromString(
                    "22222222-2222-2222-2222-222222222222")),
            new TemplateRevision(1));

    private ServerMock server;
    private PluginMock plugin;
    private PaperTrackingCoordinator coordinator;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
    }

    @AfterEach
    void tearDown() {
        if (coordinator != null) {
            coordinator.close();
        }
        MockBukkit.unmock();
    }

    @Test
    void entityUnloadScanningRetainsDisplayEvidenceAsLastConfirmed() {
        List<TrackingObservationUseCase.Request> observed = new CopyOnWriteArrayList<>();
        PaperPhysicalEntityScanner scanner = scanner(observed);
        World world = server.addSimpleWorld("world");
        ArmorStand stand = world.spawn(new Location(world, 4, 64, 7), ArmorStand.class);
        stand.getEquipment().setHelmet(trackedItem());

        scanner.scan(
                List.of(stand),
                TrackingObservationUseCase.Presence.LAST_CONFIRMED,
                "chunk-unload-entities",
                new PaperScanLimit(8));

        assertEquals(1, observed.size());
        TrackingObservationUseCase.Request request = observed.getFirst();
        assertEquals(TrackingObservationUseCase.Presence.LAST_CONFIRMED, request.presence());
        assertEquals(LocationDescriptor.Type.ARMOR_STAND, request.location().type());
        assertEquals("chunk-unload-entities-armor-stand", request.source());
    }

    @Test
    void physicalTrackingListenerPinsPaperEntityLifecycleHandlers() throws Exception {
        assertMonitorHandler("onEntitiesLoad", EntitiesLoadEvent.class);
        assertMonitorHandler("onEntitiesUnload", EntitiesUnloadEvent.class);
    }

    private static void assertMonitorHandler(String name, Class<?> eventType) throws Exception {
        Method method = PaperPhysicalTrackingListener.class.getMethod(name, eventType);
        EventHandler handler = method.getAnnotation(EventHandler.class);
        assertNotNull(handler);
        assertEquals(EventPriority.MONITOR, handler.priority());
    }

    private PaperPhysicalEntityScanner scanner(
            List<TrackingObservationUseCase.Request> observed) {
        TrackingObservationUseCase useCase = request -> {
            observed.add(request);
            return CompletableFuture.completedFuture(TrackingObservationUseCase.Result.of(
                    TrackingObservationUseCase.Status.RECORDED,
                    "ok"));
        };
        coordinator = new PaperTrackingCoordinator(
                plugin, () -> useCase, () -> 4, MetricsPort.noOp());
        return new PaperPhysicalEntityScanner(new PaperPhysicalInventoryScanner(coordinator));
    }

    private static ItemStack trackedItem() {
        return new PaperItemIdentityCodec().writeIdentity(
                ItemStack.of(Material.NETHER_STAR), IDENTITY);
    }
}
