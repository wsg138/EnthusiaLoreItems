package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
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
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

class PaperPhysicalTrackingListenerSlotChangeTest {
    private static final LoreItemIdentity IDENTITY = new LoreItemIdentity(
            new LoreDefinitionId(UUID.fromString(
                    "11111111-1111-1111-1111-111111111111")),
            new LoreInstanceId(UUID.fromString(
                    "22222222-2222-2222-2222-222222222222")),
            new TemplateRevision(1));

    private ServerMock server;
    private PluginMock plugin;
    private PaperPhysicalTrackingListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
    }

    @AfterEach
    void tearDown() {
        if (listener != null) {
            listener.close();
        }
        MockBukkit.unmock();
    }

    @Test
    void slotChangeRescansCanonicalEquipmentLocationInsteadOfTrustingEventSlot() {
        List<TrackingObservationUseCase.Request> observed = new CopyOnWriteArrayList<>();
        TrackingObservationUseCase useCase = request -> {
            observed.add(request);
            return CompletableFuture.completedFuture(TrackingObservationUseCase.Result.of(
                    TrackingObservationUseCase.Status.RECORDED,
                    "ok"));
        };
        listener = new PaperPhysicalTrackingListener(
                plugin, () -> useCase, () -> 4, MetricsPort.noOp());
        PlayerMock player = server.addPlayer();
        ItemStack tracked = new PaperItemIdentityCodec().writeIdentity(
                ItemStack.of(Material.NETHER_STAR), IDENTITY);
        player.getInventory().setHelmet(tracked);
        Inventory chest = server.createInventory(null, 9);
        player.openInventory(chest);
        PlayerInventorySlotChangeEvent event = new PlayerInventorySlotChangeEvent(
                player,
                0,
                ItemStack.empty(),
                tracked);

        listener.onSlotChange(event);
        server.getScheduler().performOneTick();

        assertEquals(1, observed.size());
        TrackingObservationUseCase.Request request = observed.getFirst();
        assertEquals(IDENTITY, request.identity());
        assertEquals(LocationDescriptor.Type.PLAYER_INVENTORY, request.location().type());
        assertEquals("armor:3", request.location().containerPath());
        assertEquals(
                TrackingObservationUseCase.EvidenceMode.AUTHORITATIVE_TRANSITION,
                request.mode());
        assertEquals("inventory-slot-change", request.source());
    }
}
