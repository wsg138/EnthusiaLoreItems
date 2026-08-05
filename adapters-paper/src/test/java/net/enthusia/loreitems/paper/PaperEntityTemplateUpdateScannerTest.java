package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

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
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class PaperEntityTemplateUpdateScannerTest {
    private static final LoreItemIdentity IDENTITY = new LoreItemIdentity(
            new LoreDefinitionId(UUID.fromString(
                    "11111111-1111-1111-1111-111111111111")),
            new LoreInstanceId(UUID.fromString(
                    "22222222-2222-2222-2222-222222222222")),
            new TemplateRevision(1));

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
        display.setItemStack(new PaperItemIdentityCodec().writeIdentity(
                ItemStack.of(Material.NETHER_STAR), IDENTITY));

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
    void ignoresUnsupportedEntities() {
        ArmorStand stand = world.spawn(
                new Location(world, 5, 64, 5), ArmorStand.class);

        assertNull(new PaperEntityTemplateUpdateScanner().scan(stand));
    }
}
