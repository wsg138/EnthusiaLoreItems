package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.papermc.paper.event.player.PlayerItemFrameChangeEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.DisplayItemObservationUseCase;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.domain.LocationDescriptor;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class PaperDisplayItemListenerTest {
    private static final int FIRST_REQUEST_COUNT = 1;
    private static final LoreItemIdentity IDENTITY = new LoreItemIdentity(
            new LoreDefinitionId(UUID.fromString(
                    "11111111-1111-1111-1111-111111111111")),
            new LoreInstanceId(UUID.fromString(
                    "22222222-2222-2222-2222-222222222222")),
            new TemplateRevision(3));

    private ServerMock server;
    private PlayerMock player;
    private RecordingUseCase useCase;
    private PaperDisplayItemListener listener;
    private PaperItemIdentityCodec identityCodec;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer();
        Plugin plugin = MockBukkit.createMockPlugin();
        useCase = new RecordingUseCase();
        listener = new PaperDisplayItemListener(plugin, () -> useCase, 16);
        identityCodec = new PaperItemIdentityCodec();
    }

    @AfterEach
    void tearDown() {
        listener.close();
        MockBukkit.unmock();
    }

    @Test
    void ordinaryMobsCannotPickupTrackedOrMalformedItemsButPlayersAndVanillaRemainAllowed() {
        Zombie zombie = (Zombie) player.getWorld().spawnEntity(
                player.getLocation(), EntityType.ZOMBIE);
        Item tracked = drop(trackedItem());
        Item malformed = drop(malformedTrackedItem());
        Item ordinary = drop(ItemStack.of(Material.DIAMOND));

        EntityPickupItemEvent trackedPickup = new EntityPickupItemEvent(zombie, tracked, 0);
        listener.onEntityPickupItem(trackedPickup);
        assertTrue(trackedPickup.isCancelled());

        EntityPickupItemEvent malformedPickup =
                new EntityPickupItemEvent(zombie, malformed, 0);
        listener.onEntityPickupItem(malformedPickup);
        assertTrue(malformedPickup.isCancelled());

        EntityPickupItemEvent ordinaryPickup = new EntityPickupItemEvent(zombie, ordinary, 0);
        listener.onEntityPickupItem(ordinaryPickup);
        assertFalse(ordinaryPickup.isCancelled());

        EntityPickupItemEvent playerPickup = new EntityPickupItemEvent(player, tracked, 0);
        listener.onEntityPickupItem(playerPickup);
        assertFalse(playerPickup.isCancelled());
    }

    @Test
    void itemFramePlacementAndRemovalRecordExactPresentThenLastConfirmedEvidence() {
        ItemFrame frame = (ItemFrame) player.getWorld().spawnEntity(
                player.getLocation(), EntityType.ITEM_FRAME);
        ItemStack tracked = trackedItem();
        PlayerItemFrameChangeEvent place = new PlayerItemFrameChangeEvent(
                player,
                frame,
                tracked,
                PlayerItemFrameChangeEvent.ItemFrameChangeAction.PLACE);

        listener.onItemFrameChange(place);
        frame.setItem(tracked);
        server.getScheduler().performOneTick();

        assertEquals(1, useCase.requests.size());
        DisplayItemObservationUseCase.Request present = useCase.requests.getFirst();
        assertEquals(DisplayItemObservationUseCase.Presence.PRESENT, present.presence());
        assertEquals(LocationDescriptor.Type.ITEM_FRAME, present.location().type());
        assertEquals("item", present.location().containerPath());

        PlayerItemFrameChangeEvent remove = new PlayerItemFrameChangeEvent(
                player,
                frame,
                ItemStack.of(Material.AIR),
                PlayerItemFrameChangeEvent.ItemFrameChangeAction.REMOVE);
        listener.onItemFrameChange(remove);
        frame.setItem(ItemStack.of(Material.AIR));
        server.getScheduler().performOneTick();

        assertEquals(2, useCase.requests.size());
        DisplayItemObservationUseCase.Request absent = useCase.requests.get(1);
        assertEquals(DisplayItemObservationUseCase.Presence.ABSENT, absent.presence());
        assertEquals(present.location(), absent.location());
    }

    @Test
    void boundedPersistenceQueueDrainsInsteadOfDroppingBusyObservations() {
        listener.close();
        BlockingUseCase blockingUseCase = new BlockingUseCase();
        listener = new PaperDisplayItemListener(
                MockBukkit.createMockPlugin(),
                () -> blockingUseCase,
                1);
        ItemFrame firstFrame = (ItemFrame) player.getWorld().spawnEntity(
                player.getLocation(), EntityType.ITEM_FRAME);
        ItemFrame secondFrame = (ItemFrame) player.getWorld().spawnEntity(
                player.getLocation().add(1.0, 0.0, 0.0), EntityType.ITEM_FRAME);

        listener.onItemFrameChange(new PlayerItemFrameChangeEvent(
                player,
                firstFrame,
                trackedItem(),
                PlayerItemFrameChangeEvent.ItemFrameChangeAction.PLACE));
        firstFrame.setItem(trackedItem());
        listener.onItemFrameChange(new PlayerItemFrameChangeEvent(
                player,
                secondFrame,
                trackedItem(),
                PlayerItemFrameChangeEvent.ItemFrameChangeAction.PLACE));
        secondFrame.setItem(trackedItem());
        server.getScheduler().performOneTick();

        assertEquals(1, blockingUseCase.requests.size());
        blockingUseCase.first.complete(DisplayItemObservationUseCase.Result.of(
                DisplayItemObservationUseCase.Status.RECORDED,
                "Recorded."));
        assertEquals(2, blockingUseCase.requests.size());
    }

    // The bounded loop deliberately creates distinct immutable identities and event snapshots.
    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    @Test
    void oneDisplaySlotHasAHardIdentityCandidateLimit() {
        ItemFrame frame = (ItemFrame) player.getWorld().spawnEntity(
                player.getLocation(), EntityType.ITEM_FRAME);
        for (int index = 1; index <= 20; index++) {
            listener.onItemFrameChange(new PlayerItemFrameChangeEvent(
                    player,
                    frame,
                    trackedItem(identityForIndex(index)),
                    PlayerItemFrameChangeEvent.ItemFrameChangeAction.PLACE));
        }

        server.getScheduler().performOneTick();

        assertEquals(16, useCase.requests.size());
        assertTrue(useCase.requests.stream().allMatch(request ->
                request.presence() == DisplayItemObservationUseCase.Presence.ABSENT));
    }

    @Test
    void armorStandPlacementRemovalAndBreakUseTheExactEquipmentSlot() {
        ArmorStand stand = (ArmorStand) player.getWorld().spawnEntity(
                player.getLocation(), EntityType.ARMOR_STAND);
        ItemStack tracked = trackedItem();
        PlayerArmorStandManipulateEvent place = new PlayerArmorStandManipulateEvent(
                player,
                stand,
                tracked,
                ItemStack.of(Material.AIR),
                EquipmentSlot.HEAD,
                EquipmentSlot.HAND);

        listener.onArmorStandManipulate(place);
        stand.getEquipment().setItem(EquipmentSlot.HEAD, tracked);
        server.getScheduler().performOneTick();

        assertEquals(1, useCase.requests.size());
        DisplayItemObservationUseCase.Request present = useCase.requests.getFirst();
        assertEquals(DisplayItemObservationUseCase.Presence.PRESENT, present.presence());
        assertEquals(LocationDescriptor.Type.ARMOR_STAND, present.location().type());
        assertEquals("slot:head", present.location().containerPath());

        PlayerArmorStandManipulateEvent remove = new PlayerArmorStandManipulateEvent(
                player,
                stand,
                ItemStack.of(Material.AIR),
                tracked,
                EquipmentSlot.HEAD,
                EquipmentSlot.HAND);
        listener.onArmorStandManipulate(remove);
        stand.getEquipment().setItem(EquipmentSlot.HEAD, ItemStack.of(Material.AIR));
        server.getScheduler().performOneTick();

        assertEquals(2, useCase.requests.size());
        assertEquals(
                DisplayItemObservationUseCase.Presence.ABSENT,
                useCase.requests.get(1).presence());

        stand.getEquipment().setItem(EquipmentSlot.HEAD, tracked);
        listener.onArmorStandDamage(new EntityDamageEvent(
                stand,
                EntityDamageEvent.DamageCause.FIRE,
                DamageSource.builder(DamageType.IN_FIRE).build(),
                10.0));
        stand.remove();
        server.getScheduler().performOneTick();

        assertEquals(3, useCase.requests.size());
        assertEquals(
                DisplayItemObservationUseCase.Presence.ABSENT,
                useCase.requests.get(2).presence());
        assertEquals("armor-stand-damage", useCase.requests.get(2).source());
    }

    private ItemStack trackedItem() {
        return trackedItem(IDENTITY);
    }

    private ItemStack trackedItem(LoreItemIdentity identity) {
        return identityCodec.writeIdentity(ItemStack.of(Material.DIAMOND_HELMET), identity);
    }

    private static LoreItemIdentity identityForIndex(int index) {
        return new LoreItemIdentity(
                IDENTITY.definitionId(),
                new LoreInstanceId(new UUID(0L, index)),
                IDENTITY.appliedRevision());
    }

    private ItemStack malformedTrackedItem() {
        ItemStack malformed = trackedItem();
        malformed.setAmount(2);
        return malformed;
    }

    private Item drop(ItemStack item) {
        Location location = player.getLocation();
        return player.getWorld().dropItem(location, item);
    }

    private static final class BlockingUseCase implements DisplayItemObservationUseCase {
        private final List<Request> requests = new ArrayList<>();
        private final CompletableFuture<Result> first = new CompletableFuture<>();

        @Override
        public CompletionStage<Result> record(Request request) {
            requests.add(request);
            if (requests.size() == FIRST_REQUEST_COUNT) {
                return first;
            }
            return CompletableFuture.completedFuture(Result.of(
                    Status.RECORDED,
                    "Recorded."));
        }
    }

    private static final class RecordingUseCase implements DisplayItemObservationUseCase {
        private final List<Request> requests = new ArrayList<>();

        @Override
        public CompletionStage<Result> record(Request request) {
            requests.add(request);
            return CompletableFuture.completedFuture(Result.of(
                    Status.RECORDED,
                    "Recorded."));
        }
    }
}
