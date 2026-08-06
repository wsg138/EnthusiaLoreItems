package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class PaperEntityTemplateUpdateReferenceNullTest {
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
    void emptyItemDisplayIsNotResolved() {
        ItemDisplay display = world.spawn(
                new Location(world, 1, 64, 1), ItemDisplay.class);
        display.setItemStack(null);
        PaperEntityTemplateUpdateReference reference = Objects.requireNonNull(
                PaperEntityTemplateUpdateReference.capture(display));

        assertTrue(reference.resolve(plugin).isEmpty());
    }

    @Test
    void resolvedItemDisplayHandlesStackClearedBeforeRemoval() {
        ItemDisplay display = world.spawn(
                new Location(world, 2, 64, 2), ItemDisplay.class);
        display.setItemStack(ItemStack.of(Material.DIAMOND_SWORD));
        PaperEntityTemplateUpdateReference reference = Objects.requireNonNull(
                PaperEntityTemplateUpdateReference.capture(display));
        PaperEntityTemplateUpdateReference.Resolved resolved =
                reference.resolve(plugin).orElseThrow();

        display.setItemStack(null);

        assertFalse(resolved.remove());
        assertNull(resolved.readStored());
    }
}
