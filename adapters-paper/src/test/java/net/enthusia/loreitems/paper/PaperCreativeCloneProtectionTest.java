package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
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

class PaperCreativeCloneProtectionTest {
    private static final LoreItemIdentity IDENTITY = new LoreItemIdentity(
            new LoreDefinitionId(UUID.fromString(
                    "11111111-1111-1111-1111-111111111111")),
            new LoreInstanceId(UUID.fromString(
                    "22222222-2222-2222-2222-222222222222")),
            new TemplateRevision(3));

    private ServerMock server;
    private PlayerMock player;
    private PaperTrackedItemProtectionListener listener;
    private PaperItemIdentityCodec identityCodec;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer();
        player.setGameMode(GameMode.CREATIVE);
        Plugin plugin = MockBukkit.createMockPlugin();
        listener = new PaperTrackedItemProtectionListener(
                plugin,
                () -> {
                    throw new AssertionError("Creative clone protection must not invoke void loss");
                },
                4);
        identityCodec = new PaperItemIdentityCodec();
    }

    @AfterEach
    void tearDown() {
        listener.close();
        MockBukkit.unmock();
    }

    @Test
    void creativeCloneRejectsDirectAndNestedIdentityEvidenceButAllowsOrdinaryItems() {
        Inventory inventory = server.createInventory(null, 9);
        inventory.setItem(0, trackedItem());
        inventory.setItem(1, shulkerContaining(trackedItem()));
        inventory.setItem(2, ItemStack.of(Material.DIAMOND));
        player.openInventory(inventory);

        InventoryClickEvent direct = cloneEvent(0);
        listener.onCreativeClone(direct);
        assertTrue(direct.isCancelled());

        InventoryClickEvent nested = cloneEvent(1);
        listener.onCreativeClone(nested);
        assertTrue(nested.isCancelled());

        InventoryClickEvent ordinary = cloneEvent(2);
        listener.onCreativeClone(ordinary);
        assertFalse(ordinary.isCancelled());
    }

    private InventoryClickEvent cloneEvent(int rawSlot) {
        return new InventoryClickEvent(
                player.getOpenInventory(),
                InventoryType.SlotType.CONTAINER,
                rawSlot,
                ClickType.MIDDLE,
                InventoryAction.CLONE_STACK);
    }

    private ItemStack trackedItem() {
        return identityCodec.writeIdentity(ItemStack.of(Material.NETHER_STAR), IDENTITY);
    }

    private static ItemStack shulkerContaining(ItemStack nested) {
        ItemStack item = ItemStack.of(Material.SHULKER_BOX);
        BlockStateMeta meta = assertInstanceOf(BlockStateMeta.class, item.getItemMeta());
        ShulkerBox shulker = assertInstanceOf(ShulkerBox.class, meta.getBlockState());
        shulker.getInventory().setItem(0, nested);
        meta.setBlockState(shulker);
        assertTrue(item.setItemMeta(meta));
        return item;
    }
}
