package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Objects;
import java.util.UUID;
import net.enthusia.loreitems.application.ItemIdentityReadResult;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.PreparedTemplateUpdate;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.GlowItemFrame;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class PaperEntityTemplateUpdateReferenceTest {
    private static final LoreDefinitionId DEFINITION_ID = new LoreDefinitionId(
            UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final LoreInstanceId INSTANCE_ID = new LoreInstanceId(
            UUID.fromString("22222222-2222-2222-2222-222222222222"));
    private static final LoreItemIdentity OBSERVED = new LoreItemIdentity(
            DEFINITION_ID, INSTANCE_ID, new TemplateRevision(1));
    private static final LoreItemIdentity TARGET = new LoreItemIdentity(
            DEFINITION_ID, INSTANCE_ID, new TemplateRevision(2));

    private ServerMock server;
    private Plugin plugin;
    private World world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        world = server.addSimpleWorld("world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void updatesDroppedItemAndVerifiesTheStoredEntityStack() {
        Item item = world.dropItem(new Location(world, 1, 64, 1), tracked("Old"));

        assertApplied(item);

        assertTarget(item.getItemStack());
    }

    @Test
    void updatesGlowItemFrameWithoutReplacingTheEntity() {
        GlowItemFrame frame = world.spawn(
                new Location(world, 2, 64, 2), GlowItemFrame.class);
        frame.setItem(tracked("Old"), false);

        assertApplied(frame);

        assertTarget(frame.getItem());
    }


    @Test
    void updatesNormalItemFrameWithoutReplacingTheEntity() {
        ItemFrame frame = world.spawn(
                new Location(world, 3, 64, 3), ItemFrame.class);
        frame.setItem(tracked("Old"), false);

        assertApplied(frame);

        assertTarget(frame.getItem());
    }

    @Test
    void updatesOneArmorStandSlotWithoutChangingSiblingEquipment() {
        ArmorStand stand = world.spawn(
                new Location(world, 4, 64, 4), ArmorStand.class);
        ItemStack unrelatedBoots = ItemStack.of(Material.LEATHER_BOOTS);
        stand.getEquipment().setHelmet(tracked("Old"));
        stand.getEquipment().setBoots(unrelatedBoots);
        PaperEntityTemplateUpdateReference reference =
                PaperEntityTemplateUpdateReference.armorStand(stand, EquipmentSlot.HEAD);

        PaperTemplateUpdateOperator.ApplyResult result =
                new PaperTemplateUpdateOperator().apply(plugin, reference, preparedUpdate());

        assertEquals(PaperTemplateUpdateOperator.ApplyResult.Status.APPLIED, result.status());
        assertTarget(stand.getEquipment().getHelmet());
        assertEquals(unrelatedBoots, stand.getEquipment().getBoots());
    }

    @Test
    void updatesItemDisplayAndVerifiesTheStoredDisplayStack() {
        ItemDisplay display = world.spawn(
                new Location(world, 3, 64, 3), ItemDisplay.class);
        display.setItemStack(tracked("Old"));

        assertApplied(display);

        assertTarget(display.getItemStack());
    }

    private void assertApplied(Entity entity) {
        PaperEntityTemplateUpdateReference reference =
                Objects.requireNonNull(PaperEntityTemplateUpdateReference.capture(entity));
        PaperTemplateUpdateOperator.ApplyResult result =
                new PaperTemplateUpdateOperator().apply(plugin, reference, preparedUpdate());

        assertEquals(PaperTemplateUpdateOperator.ApplyResult.Status.APPLIED, result.status());
        assertTrue(result.beforeFingerprint() != null && !result.beforeFingerprint().isBlank());
        assertTrue(result.afterFingerprint() != null && !result.afterFingerprint().isBlank());
    }

    private static PreparedTemplateUpdate preparedUpdate() {
        return new PreparedTemplateUpdate(
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                "claim-token",
                OBSERVED,
                TARGET,
                new PaperItemTemplateCodec().encode(named("New")),
                Long.MAX_VALUE);
    }

    private static ItemStack tracked(String name) {
        return new PaperItemIdentityCodec().writeIdentity(named(name), OBSERVED);
    }

    private static ItemStack named(String name) {
        ItemStack item = ItemStack.of(Material.DIAMOND_SWORD);
        ItemMeta meta = Objects.requireNonNull(item.getItemMeta());
        meta.displayName(Component.text(name));
        assertTrue(item.setItemMeta(meta));
        return item;
    }

    private static void assertTarget(ItemStack item) {
        assertEquals(Component.text("New"), item.getItemMeta().displayName());
        ItemIdentityReadResult.Tracked tracked = assertInstanceOf(
                ItemIdentityReadResult.Tracked.class,
                new PaperItemIdentityCodec().readIdentity(item));
        assertEquals(TARGET, tracked.identity());
    }
}
