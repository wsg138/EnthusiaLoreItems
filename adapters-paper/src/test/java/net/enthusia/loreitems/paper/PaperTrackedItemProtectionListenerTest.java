package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.destroystokyo.paper.event.player.PlayerReadyArrowEvent;
import io.papermc.paper.event.block.BlockPreDispenseEvent;
import io.papermc.paper.event.entity.EntityCompostItemEvent;
import io.papermc.paper.event.entity.EntityDamageItemEvent;
import io.papermc.paper.event.player.PlayerFlowerPotManipulateEvent;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.PreparedVoidLoss;
import net.enthusia.loreitems.application.VoidLossUseCase;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.ShulkerBox;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Item;
import org.bukkit.event.block.BlockCookEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.inventory.BrewingStandFuelEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class PaperTrackedItemProtectionListenerTest {
    private static final LoreItemIdentity IDENTITY = new LoreItemIdentity(
            new LoreDefinitionId(UUID.fromString(
                    "11111111-1111-1111-1111-111111111111")),
            new LoreInstanceId(UUID.fromString(
                    "22222222-2222-2222-2222-222222222222")),
            new TemplateRevision(3));

    private ServerMock server;
    private PlayerMock player;
    private RecordingVoidLossUseCase useCase;
    private PaperTrackedItemProtectionListener listener;
    private PaperItemIdentityCodec identityCodec;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer();
        Plugin plugin = MockBukkit.createMockPlugin();
        useCase = new RecordingVoidLossUseCase();
        listener = new PaperTrackedItemProtectionListener(plugin, () -> useCase, 4);
        identityCodec = new PaperItemIdentityCodec();
    }

    @AfterEach
    void tearDown() {
        listener.close();
        MockBukkit.unmock();
    }

    @Test
    void trackedAndMalformedItemsCannotDespawnCombustOrMerge() {
        Item tracked = drop(trackedItem());
        Item malformed = drop(malformedTrackedItem());
        Item ordinary = drop(ItemStack.of(Material.DIAMOND));

        ItemDespawnEvent despawn = new ItemDespawnEvent(tracked, tracked.getLocation());
        listener.onItemDespawn(despawn);
        assertTrue(despawn.isCancelled());

        EntityCombustEvent combust = new EntityCombustEvent(malformed, 20.0F);
        listener.onItemCombust(combust);
        assertTrue(combust.isCancelled());

        ItemMergeEvent mergeTrackedSource = new ItemMergeEvent(tracked, ordinary);
        listener.onItemMerge(mergeTrackedSource);
        assertTrue(mergeTrackedSource.isCancelled());

        ItemMergeEvent mergeTrackedTarget = new ItemMergeEvent(ordinary, malformed);
        listener.onItemMerge(mergeTrackedTarget);
        assertTrue(mergeTrackedTarget.isCancelled());

        ItemDespawnEvent ordinaryDespawn =
                new ItemDespawnEvent(ordinary, ordinary.getLocation());
        listener.onItemDespawn(ordinaryDespawn);
        assertFalse(ordinaryDespawn.isCancelled());
    }

    @Test
    void nestedTrackedItemsProtectDroppedContainersFromDespawnAndDamage() {
        Item nested = drop(shulkerContaining(trackedItem()));

        ItemDespawnEvent despawn = new ItemDespawnEvent(nested, nested.getLocation());
        listener.onItemDespawn(despawn);
        assertTrue(despawn.isCancelled());

        EntityDamageEvent fire = new EntityDamageEvent(
                nested,
                EntityDamageEvent.DamageCause.FIRE,
                DamageSource.builder(DamageType.IN_FIRE).build(),
                2.0);
        listener.onItemDamage(fire);
        assertTrue(fire.isCancelled());
        assertFalse(useCase.prepareCalled);
    }

    @Test
    void trackedDurabilityAndEnvironmentalDamageAreCancelled() {
        ItemStack trackedStack = trackedItem();
        PlayerItemDamageEvent durability =
                new PlayerItemDamageEvent(player, trackedStack, 1, 1);
        listener.onDurabilityDamage(durability);
        assertTrue(durability.isCancelled());

        EntityDamageItemEvent entityDurability =
                new EntityDamageItemEvent(player, trackedStack, 1);
        listener.onEntityDurabilityDamage(entityDurability);
        assertTrue(entityDurability.isCancelled());

        Item trackedEntity = drop(trackedStack);
        EntityDamageEvent fire = new EntityDamageEvent(
                trackedEntity,
                EntityDamageEvent.DamageCause.FIRE,
                DamageSource.builder(DamageType.IN_FIRE).build(),
                2.0);
        listener.onItemDamage(fire);
        assertTrue(fire.isCancelled());
        assertFalse(useCase.prepareCalled);

        Item ordinary = drop(ItemStack.of(Material.DIAMOND));
        EntityDamageEvent ordinaryFire = new EntityDamageEvent(
                ordinary,
                EntityDamageEvent.DamageCause.FIRE,
                DamageSource.builder(DamageType.IN_FIRE).build(),
                2.0);
        listener.onItemDamage(ordinaryFire);
        assertFalse(ordinaryFire.isCancelled());

        EntityDamageItemEvent ordinaryDurability =
                new EntityDamageItemEvent(player, ItemStack.of(Material.IRON_SWORD), 1);
        listener.onEntityDurabilityDamage(ordinaryDurability);
        assertFalse(ordinaryDurability.isCancelled());
    }

    @Test
    void trackedItemsCannotBeConsumedCookedOrUsedAsFuel() {
        ItemStack tracked = trackedItem();
        PlayerItemConsumeEvent consume =
                new PlayerItemConsumeEvent(player, tracked, EquipmentSlot.HAND);
        listener.onConsume(consume);
        assertTrue(consume.isCancelled());

        PlayerItemConsumeEvent malformedConsume =
                new PlayerItemConsumeEvent(player, malformedTrackedItem(), EquipmentSlot.HAND);
        listener.onConsume(malformedConsume);
        assertTrue(malformedConsume.isCancelled());

        Block block = player.getWorld().getBlockAt(0, 64, 0);
        BlockCookEvent cook = new BlockCookEvent(
                block, tracked, ItemStack.of(Material.IRON_INGOT), null);
        listener.onCook(cook);
        assertTrue(cook.isCancelled());

        FurnaceBurnEvent furnaceFuel = new FurnaceBurnEvent(block, tracked, 200);
        listener.onFurnaceFuel(furnaceFuel);
        assertTrue(furnaceFuel.isCancelled());

        BrewingStandFuelEvent brewingFuel =
                new BrewingStandFuelEvent(block, tracked, 20);
        listener.onBrewingFuel(brewingFuel);
        assertTrue(brewingFuel.isCancelled());

        EntityCompostItemEvent compost =
                new EntityCompostItemEvent(player, block, tracked, true);
        listener.onEntityCompost(compost);
        assertTrue(compost.isCancelled());

        PlayerFlowerPotManipulateEvent flowerPot =
                new PlayerFlowerPotManipulateEvent(player, block, tracked, true);
        listener.onFlowerPotManipulate(flowerPot);
        assertTrue(flowerPot.isCancelled());

        PlayerItemConsumeEvent ordinaryConsume = new PlayerItemConsumeEvent(
                player, ItemStack.of(Material.APPLE), EquipmentSlot.HAND);
        listener.onConsume(ordinaryConsume);
        assertFalse(ordinaryConsume.isCancelled());

        EntityCompostItemEvent ordinaryCompost = new EntityCompostItemEvent(
                player, block, ItemStack.of(Material.WHEAT_SEEDS), true);
        listener.onEntityCompost(ordinaryCompost);
        assertFalse(ordinaryCompost.isCancelled());
    }

    @Test
    void trackedInputsCannotBeTakenFromResultSlots() {
        Inventory inventory = server.createInventory(null, 9);
        inventory.setItem(0, trackedItem());
        InventoryClickEvent resultClick = new InventoryClickEvent(
                player.openInventory(inventory),
                InventoryType.SlotType.RESULT,
                2,
                ClickType.LEFT,
                InventoryAction.PICKUP_ALL);

        listener.onInventoryResultClick(resultClick);

        assertTrue(resultClick.isCancelled());

        inventory.setItem(0, ItemStack.of(Material.DIAMOND));
        InventoryClickEvent ordinaryClick = new InventoryClickEvent(
                player.getOpenInventory(),
                InventoryType.SlotType.RESULT,
                2,
                ClickType.LEFT,
                InventoryAction.PICKUP_ALL);
        listener.onInventoryResultClick(ordinaryClick);
        assertFalse(ordinaryClick.isCancelled());
    }

    @Test
    void trackedPlacementArrowAndDispenseConversionsAreCancelled() {
        ItemStack tracked = trackedItem();
        Block placed = player.getWorld().getBlockAt(1, 64, 0);
        Block against = player.getWorld().getBlockAt(1, 63, 0);
        BlockPlaceEvent place = new BlockPlaceEvent(
                placed,
                placed.getState(),
                against,
                tracked,
                player,
                true,
                EquipmentSlot.HAND);
        listener.onBlockPlace(place);
        assertTrue(place.isCancelled());

        PlayerReadyArrowEvent readyArrow = new PlayerReadyArrowEvent(
                player, ItemStack.of(Material.BOW), tracked);
        listener.onReadyArrow(readyArrow);
        assertTrue(readyArrow.isCancelled());

        BlockPreDispenseEvent dispense = new BlockPreDispenseEvent(against, tracked, 0);
        listener.onDispense(dispense);
        assertTrue(dispense.isCancelled());
    }

    @Test
    void voidDamagePersistsIntentThenRemovesAndCompletesExactEntity() {
        Item item = drop(trackedItem());
        assertTrue(item.teleport(new Location(
                item.getWorld(),
                0.0,
                item.getWorld().getMinHeight() - 1.0,
                0.0)));
        EntityDamageEvent voidDamage = new EntityDamageEvent(
                item,
                EntityDamageEvent.DamageCause.VOID,
                DamageSource.builder(DamageType.OUT_OF_WORLD).build(),
                4.0);

        listener.onItemDamage(voidDamage);
        server.getScheduler().performOneTick();

        assertTrue(voidDamage.isCancelled());
        assertTrue(useCase.prepareCalled);
        assertEquals(IDENTITY, useCase.request.identity());
        assertTrue(useCase.completeCalled);
        assertTrue(item.isDead() || !item.isValid());
    }

    private ItemStack trackedItem() {
        return identityCodec.writeIdentity(ItemStack.of(Material.DIAMOND_SWORD), IDENTITY);
    }

    private ItemStack malformedTrackedItem() {
        ItemStack malformed = trackedItem();
        malformed.setAmount(2);
        return malformed;
    }

    private ItemStack shulkerContaining(ItemStack nested) {
        ItemStack item = ItemStack.of(Material.SHULKER_BOX);
        BlockStateMeta meta = assertInstanceOf(BlockStateMeta.class, item.getItemMeta());
        ShulkerBox shulker = assertInstanceOf(ShulkerBox.class, meta.getBlockState());
        shulker.getInventory().setItem(0, nested);
        meta.setBlockState(shulker);
        assertTrue(item.setItemMeta(meta));
        return item;
    }

    private Item drop(ItemStack item) {
        return player.getWorld().dropItem(player.getLocation(), item);
    }

    private static final class RecordingVoidLossUseCase implements VoidLossUseCase {
        private boolean prepareCalled;
        private boolean completeCalled;
        private Request request;

        @Override
        public CompletionStage<PrepareResult> prepare(Request candidate) {
            prepareCalled = true;
            request = candidate;
            PreparedVoidLoss loss = new PreparedVoidLoss(
                    UUID.fromString("33333333-3333-3333-3333-333333333333"),
                    candidate.identity(),
                    candidate.entityId(),
                    candidate.locationKey(),
                    UUID.fromString("44444444-4444-4444-4444-444444444444"),
                    1_000L,
                    31_000L);
            return CompletableFuture.completedFuture(PrepareResult.prepared(loss));
        }

        @Override
        public CompletionStage<Boolean> complete(PreparedVoidLoss loss) {
            completeCalled = true;
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletionStage<Boolean> abort(PreparedVoidLoss loss, String reason) {
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletionStage<Boolean> requireReview(PreparedVoidLoss loss, String reason) {
            return CompletableFuture.completedFuture(true);
        }
    }
}
