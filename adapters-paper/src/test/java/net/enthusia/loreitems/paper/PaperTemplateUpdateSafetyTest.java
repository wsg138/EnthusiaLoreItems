package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.enthusia.loreitems.application.ItemIdentityReadResult;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.PreparedTemplateUpdate;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class PaperTemplateUpdateSafetyTest {
    private static final LoreDefinitionId DEFINITION_ID = new LoreDefinitionId(
            UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final LoreInstanceId INSTANCE_ID = new LoreInstanceId(
            UUID.fromString("22222222-2222-2222-2222-222222222222"));
    private static final TemplateRevision REVISION_ONE = new TemplateRevision(1L);
    private static final TemplateRevision REVISION_TWO = new TemplateRevision(2L);

    private PlayerMock player;
    private Plugin plugin;
    private PaperItemIdentityCodec identityCodec;
    private PaperTemplateUpdateOperator operator;

    @BeforeEach
    void setUp() {
        ServerMock server = MockBukkit.mock();
        player = server.addPlayer();
        plugin = MockBukkit.createMockPlugin();
        identityCodec = new PaperItemIdentityCodec();
        operator = new PaperTemplateUpdateOperator();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void clearsTemplateShulkerContentsInsteadOfCloningThemIntoAnInstance() {
        placeOriginal();
        ItemStack desired = ItemStack.of(Material.SHULKER_BOX);
        BlockStateMeta desiredMeta = assertInstanceOf(
                BlockStateMeta.class, desired.getItemMeta());
        desiredMeta.displayName(Component.text("New Box"));
        ShulkerBox desiredBox = assertInstanceOf(
                ShulkerBox.class, desiredMeta.getBlockState());
        desiredBox.getInventory().setItem(5, ItemStack.of(Material.DIAMOND, 3));
        desiredMeta.setBlockState(desiredBox);
        assertTrue(desired.setItemMeta(desiredMeta));

        PaperTemplateUpdateOperator.ApplyResult result = apply(desired);

        assertEquals(PaperTemplateUpdateOperator.ApplyResult.Status.APPLIED, result.status());
        ItemStack stored = Objects.requireNonNull(player.getInventory().getItem(0));
        BlockStateMeta storedMeta = assertInstanceOf(BlockStateMeta.class, stored.getItemMeta());
        ShulkerBox storedBox = assertInstanceOf(ShulkerBox.class, storedMeta.getBlockState());
        assertNull(storedBox.getInventory().getItem(5));
        assertEquals(targetIdentity(), trackedIdentity(stored));
    }

    @Test
    void clearsTemplateBundleContentsInsteadOfCloningThemIntoAnInstance() {
        placeOriginal();
        ItemStack desired = ItemStack.of(Material.BUNDLE);
        BundleMeta desiredMeta = assertInstanceOf(BundleMeta.class, desired.getItemMeta());
        desiredMeta.setItems(List.of(ItemStack.of(Material.DIAMOND, 3)));
        assertTrue(desired.setItemMeta(desiredMeta));

        PaperTemplateUpdateOperator.ApplyResult result = apply(desired);

        assertEquals(PaperTemplateUpdateOperator.ApplyResult.Status.APPLIED, result.status());
        ItemStack stored = Objects.requireNonNull(player.getInventory().getItem(0));
        BundleMeta storedMeta = assertInstanceOf(BundleMeta.class, stored.getItemMeta());
        assertTrue(storedMeta.getItems().isEmpty());
        assertEquals(targetIdentity(), trackedIdentity(stored));
    }

    @Test
    void comparatorRejectsAChangedNestedLoreIdentity() {
        ItemStack firstNested = tracked(named("Nested Blade"), INSTANCE_ID);
        ItemStack secondNested = tracked(named("Nested Blade"), new LoreInstanceId(UUID.randomUUID()));
        PaperTemplateItemComparator comparator = new PaperTemplateItemComparator(identityCodec);

        assertFalse(comparator.matches(
                shulkerContaining(firstNested),
                shulkerContaining(secondNested)));
    }

    private void placeOriginal() {
        player.getInventory().setItem(0, tracked(named("Old Blade"), INSTANCE_ID));
    }

    private PaperTemplateUpdateOperator.ApplyResult apply(ItemStack desired) {
        return operator.apply(
                plugin,
                PaperTemplateUpdateItemReference.root(
                        new PaperInventoryReference.PlayerMain(player.getUniqueId()), 0),
                new PreparedTemplateUpdate(
                        UUID.fromString("33333333-3333-3333-3333-333333333333"),
                        "claim-token",
                        observedIdentity(),
                        targetIdentity(),
                        new PaperItemTemplateCodec().encode(desired),
                        31_000L));
    }

    private ItemStack tracked(ItemStack item, LoreInstanceId instanceId) {
        return identityCodec.writeIdentity(
                item,
                new LoreItemIdentity(DEFINITION_ID, instanceId, REVISION_ONE));
    }

    private LoreItemIdentity trackedIdentity(ItemStack item) {
        ItemIdentityReadResult.Tracked tracked = assertInstanceOf(
                ItemIdentityReadResult.Tracked.class,
                identityCodec.readIdentity(item));
        return tracked.identity();
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

    private static ItemStack named(String name) {
        ItemStack item = ItemStack.of(Material.DIAMOND_SWORD);
        ItemMeta meta = Objects.requireNonNull(item.getItemMeta());
        meta.displayName(Component.text(name));
        assertTrue(item.setItemMeta(meta));
        return item;
    }

    private static LoreItemIdentity observedIdentity() {
        return new LoreItemIdentity(DEFINITION_ID, INSTANCE_ID, REVISION_ONE);
    }

    private static LoreItemIdentity targetIdentity() {
        return new LoreItemIdentity(DEFINITION_ID, INSTANCE_ID, REVISION_TWO);
    }
}
