package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;
import java.util.UUID;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class PaperEntityTemplateUpdateScannerTest {
    private static final LoreDefinitionId DEFINITION_ID = new LoreDefinitionId(
            UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final LoreItemIdentity IDENTITY = identity(2L);

    private ServerMock server;
    private World world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void emitsItemDisplayCandidateWithReloadSafeEntityReference() {
        ItemDisplay display = world.spawn(
                new Location(world, 4, 64, 4), ItemDisplay.class);
        display.setItemStack(tracked(Material.NETHER_STAR, IDENTITY));

        PaperTemplateUpdateScanner.Candidate candidate =
                new PaperEntityTemplateUpdateScanner().scan(display);

        assertEquals(IDENTITY, candidate.identity());
        PaperEntityTemplateUpdateReference reference = assertInstanceOf(
                PaperEntityTemplateUpdateReference.class,
                candidate.reference());
        assertEquals(display.getUniqueId(), reference.entityId());
        assertEquals(PaperEntityTemplateUpdateReference.Kind.ITEM_DISPLAY, reference.kind());
    }

    @Test
    void emitsOneCandidatePerTrackedArmorStandEquipmentSlot() {
        ArmorStand stand = world.spawn(
                new Location(world, 5, 64, 5), ArmorStand.class);
        LoreItemIdentity helmetIdentity = identity(3L);
        LoreItemIdentity handIdentity = identity(4L);
        stand.getEquipment().setHelmet(tracked(Material.DIAMOND_HELMET, helmetIdentity));
        stand.getEquipment().setItemInMainHand(tracked(Material.DIAMOND_SWORD, handIdentity));
        stand.getEquipment().setBoots(ItemStack.of(Material.LEATHER_BOOTS));

        List<PaperTemplateUpdateScanner.Candidate> candidates =
                new PaperEntityTemplateUpdateScanner().scanAll(stand);

        assertEquals(2, candidates.size());
        assertArmorStandCandidate(candidates.get(0), handIdentity, EquipmentSlot.HAND);
        assertArmorStandCandidate(candidates.get(1), helmetIdentity, EquipmentSlot.HEAD);
    }

    private static void assertArmorStandCandidate(
            PaperTemplateUpdateScanner.Candidate candidate,
            LoreItemIdentity identity,
            EquipmentSlot expectedSlot) {
        assertEquals(identity, candidate.identity());
        PaperEntityTemplateUpdateReference reference = assertInstanceOf(
                PaperEntityTemplateUpdateReference.class,
                candidate.reference());
        assertEquals(PaperEntityTemplateUpdateReference.Kind.ARMOR_STAND, reference.kind());
        assertEquals(expectedSlot, reference.equipmentSlot());
    }

    private static ItemStack tracked(Material material, LoreItemIdentity identity) {
        return new PaperItemIdentityCodec().writeIdentity(ItemStack.of(material), identity);
    }

    private static LoreItemIdentity identity(long instanceId) {
        return new LoreItemIdentity(
                DEFINITION_ID,
                new LoreInstanceId(new UUID(0L, instanceId)),
                new TemplateRevision(1));
    }
}
