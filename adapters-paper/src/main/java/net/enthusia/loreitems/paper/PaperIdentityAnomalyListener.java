package net.enthusia.loreitems.paper;

import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent;
import com.destroystokyo.paper.event.player.PlayerReadyArrowEvent;
import io.papermc.paper.event.block.BlockPreDispenseEvent;
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import io.papermc.paper.event.player.PlayerItemFrameChangeEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.enthusia.loreitems.application.ItemIdentityReadResult;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.domain.LocationDescriptor;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockCookEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.BrewingStandFuelEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

public final class PaperIdentityAnomalyListener implements Listener, AutoCloseable {
    private static final int MAX_CONFLICT_PATH_LENGTH =
            LocationDescriptor.MAX_CONTAINER_PATH_LENGTH;

    private final Plugin plugin;
    private final PaperItemAnomalyReporter anomalyReporter;

    private boolean closed;

    public PaperIdentityAnomalyListener(Plugin plugin, int maxInFlight) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.anomalyReporter = new PaperItemAnomalyReporter(plugin, maxInFlight);
    }

    public void start() {
        if (closed) {
            throw new IllegalStateException("Identity anomaly listener is closed");
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            scanPlayerInventory(player, "listener-start");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        scanPlayerInventory(event.getPlayer(), "player-join");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventorySlotChange(PlayerInventorySlotChangeEvent event) {
        scanPlayerInventory(event.getPlayer(), "inventory-slot-change");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        scanPlayerInventory(player, "inventory-close-player");
        Inventory topInventory = event.getView().getTopInventory();
        if (topInventory != player.getInventory()) {
            scanStorageInventory(topInventory, player, "inventory-close-storage");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        LocationDescriptor sourceLocation = inventoryLocation(
                event.getSource(), "moving-item");
        if (sourceLocation == null) {
            return;
        }
        ObservedCopy moving = observe(
                event.getItem(), sourceLocation, "inventory-move-source");
        compareWithInventory(
                event.getDestination(), moving, "inventory-move-destination");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryPickup(InventoryPickupItemEvent event) {
        Item item = event.getItem();
        ObservedCopy dropped = observe(
                item.getItemStack(),
                droppedLocation(item),
                "inventory-pickup-item");
        compareWithInventory(
                event.getInventory(), dropped, "inventory-pickup-destination");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDrop(PlayerDropItemEvent event) {
        Item item = event.getItemDrop();
        ObservedCopy dropped = observe(
                item.getItemStack(),
                droppedLocation(item),
                "player-drop");
        compareWithInventory(event.getPlayer(), dropped, "player-drop");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        Item item = event.getItem();
        ObservedCopy dropped = observe(
                item.getItemStack(),
                droppedLocation(item),
                "player-pickup");
        compareWithInventory(player, dropped, "player-pickup");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemFrameChange(PlayerItemFrameChangeEvent event) {
        ItemFrame frame = event.getItemFrame();
        LocationDescriptor frameLocation = displayLocation(
                LocationDescriptor.Type.ITEM_FRAME,
                frame,
                "frame-item");
        ObservedCopy displayed = observe(
                frame.getItem(),
                frameLocation,
                "item-frame-current");
        ItemStack involved = event.getItemStack();
        if (involved != null) {
            observe(
                    involved,
                    playerLocation(event.getPlayer(), "item-frame-event"),
                    "item-frame-event");
        }
        compareWithInventory(event.getPlayer(), displayed, "item-frame-player-conflict");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        ArmorStand armorStand = event.getRightClicked();
        LocationDescriptor standLocation = displayLocation(
                LocationDescriptor.Type.ARMOR_STAND,
                armorStand,
                "equipment:" + event.getSlot().name().toLowerCase(java.util.Locale.ROOT));
        ObservedCopy displayed = observe(
                event.getArmorStandItem(),
                standLocation,
                "armor-stand-current");
        observe(
                event.getPlayerItem(),
                playerLocation(event.getPlayer(), "armor-stand-event"),
                "armor-stand-event");
        compareWithInventory(event.getPlayer(), displayed, "armor-stand-player-conflict");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemDespawn(ItemDespawnEvent event) {
        Item item = event.getEntity();
        observe(item.getItemStack(), droppedLocation(item), "item-despawn");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemCombust(EntityCombustEvent event) {
        if (event.getEntity() instanceof Item item) {
            observe(item.getItemStack(), droppedLocation(item), "item-combust");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemMerge(ItemMergeEvent event) {
        observe(
                event.getEntity().getItemStack(),
                droppedLocation(event.getEntity()),
                "item-merge-source");
        observe(
                event.getTarget().getItemStack(),
                droppedLocation(event.getTarget()),
                "item-merge-target");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Item item) {
            observe(item.getItemStack(), droppedLocation(item), "item-damage");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDurabilityDamage(PlayerItemDamageEvent event) {
        observe(
                event.getItem(),
                playerLocation(event.getPlayer(), "durability-item"),
                "durability-damage");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onConsume(PlayerItemConsumeEvent event) {
        observe(
                event.getItem(),
                playerLocation(event.getPlayer(), "consumed-item"),
                "item-consume");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryResultClick(InventoryClickEvent event) {
        if (event.getSlotType() != InventoryType.SlotType.RESULT) {
            return;
        }
        Inventory inventory = event.getView().getTopInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item != null) {
                observe(
                        item,
                        playerLocation(event.getWhoClicked(), "result-input:" + slot),
                        "inventory-result");
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCook(BlockCookEvent event) {
        observe(
                event.getSource(),
                blockLocation(event.getBlock(), "cook-source"),
                "block-cook");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFurnaceFuel(FurnaceBurnEvent event) {
        observe(
                event.getFuel(),
                blockLocation(event.getBlock(), "furnace-fuel"),
                "furnace-fuel");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBrewingFuel(BrewingStandFuelEvent event) {
        observe(
                event.getFuel(),
                blockLocation(event.getBlock(), "brewing-fuel"),
                "brewing-fuel");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBrew(BrewEvent event) {
        Inventory inventory = event.getContents();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item != null) {
                observe(
                        item,
                        blockLocation(event.getBlock(), "brewing-slot:" + slot),
                        "brew");
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockPlace(BlockPlaceEvent event) {
        observe(
                event.getItemInHand(),
                playerLocation(event.getPlayer(), "placed-item"),
                "block-place");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHangingPlace(HangingPlaceEvent event) {
        ItemStack item = event.getItemStack();
        if (item != null) {
            observe(item, hangingSourceLocation(event), "hanging-place");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        observeBucket(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBucketFill(PlayerBucketFillEvent event) {
        observeBucket(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onProjectileLaunch(PlayerLaunchProjectileEvent event) {
        observe(
                event.getItemStack(),
                playerLocation(event.getPlayer(), "launched-projectile"),
                "projectile-launch");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onReadyArrow(PlayerReadyArrowEvent event) {
        observe(
                event.getArrow(),
                playerLocation(event.getPlayer(), "ready-arrow"),
                "ready-arrow");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onShootBow(EntityShootBowEvent event) {
        ItemStack consumable = event.getConsumable();
        if (consumable != null) {
            observe(consumable, shooterLocation(event), "bow-consumable");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDispense(BlockPreDispenseEvent event) {
        observe(
                event.getItemStack(),
                blockLocation(event.getBlock(), "dispense-source"),
                "block-dispense");
    }

    private void observeBucket(PlayerBucketEvent event) {
        EquipmentSlot hand = event.getHand();
        ItemStack item = hand == EquipmentSlot.OFF_HAND
                ? event.getPlayer().getInventory().getItemInOffHand()
                : event.getPlayer().getInventory().getItemInMainHand();
        observe(
                item,
                playerLocation(event.getPlayer(), "bucket:" + hand.name()),
                "bucket-use");
    }

    private void scanPlayerInventory(Player player, String source) {
        Map<UUID, ObservedCopy> firstCopies = new HashMap<>();
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.getType().isAir()) {
                continue;
            }
            ObservedCopy copy = observe(
                    item,
                    playerLocation(player, "slot:" + slot),
                    source);
            recordIfDuplicate(firstCopies, copy, source);
        }
    }

    private void scanStorageInventory(
            Inventory inventory,
            Player player,
            String source) {
        Map<UUID, ObservedCopy> firstCopies = new HashMap<>();
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            LocationDescriptor location = inventoryLocation(
                    inventory, "slot:" + slot);
            if (item == null || item.getType().isAir() || location == null) {
                continue;
            }
            ObservedCopy copy = observe(item, location, source);
            recordIfDuplicate(firstCopies, copy, source);
            compareWithInventory(player, copy, source);
        }
    }

    private void compareWithInventory(
            Player player,
            ObservedCopy external,
            String source) {
        if (external == null) {
            return;
        }
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.getType().isAir()) {
                continue;
            }
            ObservedCopy inventoryCopy = observe(
                    item,
                    playerLocation(player, "slot:" + slot),
                    source);
            if (inventoryCopy != null
                    && inventoryCopy.identity().equals(external.identity())) {
                recordDuplicate(external, inventoryCopy, source);
            }
        }
    }

    private void compareWithInventory(
            Inventory inventory,
            ObservedCopy external,
            String source) {
        if (external == null) {
            return;
        }
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            LocationDescriptor location = inventoryLocation(
                    inventory, "slot:" + slot);
            if (item == null || item.getType().isAir() || location == null) {
                continue;
            }
            ObservedCopy inventoryCopy = observe(item, location, source);
            if (inventoryCopy != null
                    && inventoryCopy.identity().equals(external.identity())) {
                recordDuplicate(external, inventoryCopy, source);
            }
        }
    }

    private void recordIfDuplicate(
            Map<UUID, ObservedCopy> firstCopies,
            ObservedCopy copy,
            String source) {
        if (copy == null) {
            return;
        }
        ObservedCopy previous = firstCopies.putIfAbsent(
                copy.identity().instanceId().value(), copy);
        if (previous != null) {
            recordDuplicate(previous, copy, source);
        }
    }

    private ObservedCopy observe(
            ItemStack item,
            LocationDescriptor location,
            String source) {
        ItemIdentityReadResult result = anomalyReporter.inspect(item, location, source);
        if (result instanceof ItemIdentityReadResult.Tracked tracked) {
            return new ObservedCopy(tracked.identity(), location);
        }
        return null;
    }

    private void recordDuplicate(
            ObservedCopy first,
            ObservedCopy second,
            String source) {
        String firstDescription = describe(first.location());
        String secondDescription = describe(second.location());
        String detail = "Same lore instance observed at copy1=" + firstDescription
                + " and copy2=" + secondDescription + '.';
        String path = truncate(
                "copy1=" + firstDescription + ";copy2=" + secondDescription,
                MAX_CONFLICT_PATH_LENGTH);
        LocationDescriptor conflict = new LocationDescriptor(
                LocationDescriptor.Type.DUPLICATE_CONFLICT,
                "instance:" + first.identity().instanceId().value()
                        + ":pair:" + Integer.toUnsignedString(path.hashCode()),
                path);
        anomalyReporter.recordDuplicate(first.identity(), conflict, source, detail);
    }

    private static LocationDescriptor playerLocation(HumanEntity player, String path) {
        return new LocationDescriptor(
                LocationDescriptor.Type.PLAYER_INVENTORY,
                "player:" + player.getUniqueId(),
                path);
    }

    private static LocationDescriptor droppedLocation(Item item) {
        return new LocationDescriptor(
                LocationDescriptor.Type.DROPPED_ITEM,
                "entity:" + item.getUniqueId() + ':' + blockKey(item),
                "item-entity");
    }

    private static LocationDescriptor blockLocation(Block block, String path) {
        return new LocationDescriptor(
                LocationDescriptor.Type.BLOCK_CONTAINER,
                block.getWorld().getKey() + ":" + block.getX() + ":" + block.getY()
                        + ":" + block.getZ(),
                path);
    }

    private static LocationDescriptor inventoryLocation(
            Inventory inventory,
            String path) {
        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof Player player) {
            return playerLocation(player, path);
        }
        Location location = inventory.getLocation();
        if (location != null && location.getWorld() != null) {
            return new LocationDescriptor(
                    LocationDescriptor.Type.BLOCK_CONTAINER,
                    location.getWorld().getKey() + ":"
                            + location.getBlockX() + ":"
                            + location.getBlockY() + ":"
                            + location.getBlockZ(),
                    path);
        }
        if (holder instanceof Entity entity) {
            return new LocationDescriptor(
                    LocationDescriptor.Type.DUPLICATE_CONFLICT,
                    "entity-inventory:" + entity.getUniqueId(),
                    path);
        }
        return null;
    }

    private static LocationDescriptor displayLocation(
            LocationDescriptor.Type type,
            Entity entity,
            String path) {
        return new LocationDescriptor(
                type,
                entity.getWorld().getKey() + ":entity:" + entity.getUniqueId(),
                path);
    }

    private static LocationDescriptor hangingSourceLocation(HangingPlaceEvent event) {
        Player player = event.getPlayer();
        if (player != null) {
            return playerLocation(player, "hanging-item");
        }
        return new LocationDescriptor(
                LocationDescriptor.Type.DUPLICATE_CONFLICT,
                "hanging:" + event.getEntity().getUniqueId(),
                "placement-source");
    }

    private static LocationDescriptor shooterLocation(EntityShootBowEvent event) {
        if (event.getEntity() instanceof Player player) {
            return playerLocation(player, "bow-consumable");
        }
        return new LocationDescriptor(
                LocationDescriptor.Type.DUPLICATE_CONFLICT,
                "entity:" + event.getEntity().getUniqueId(),
                "held-consumable");
    }

    private static String blockKey(Item item) {
        return item.getWorld().getKey() + ":"
                + item.getLocation().getBlockX() + ":"
                + item.getLocation().getBlockY() + ":"
                + item.getLocation().getBlockZ();
    }

    private static String describe(LocationDescriptor location) {
        return location.type().name() + ':' + location.locationKey()
                + (location.containerPath() == null
                        ? ""
                        : ":" + location.containerPath());
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    @Override
    public void close() {
        closed = true;
        anomalyReporter.close();
        HandlerList.unregisterAll(this);
    }

    private record ObservedCopy(
            LoreItemIdentity identity,
            LocationDescriptor location) {}
}
