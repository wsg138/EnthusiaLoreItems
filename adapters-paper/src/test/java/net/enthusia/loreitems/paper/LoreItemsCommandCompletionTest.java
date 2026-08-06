package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class LoreItemsCommandCompletionTest {
    private Plugin plugin;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        player = MockBukkit.getMock().addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void hidesAdministrativeRoutesWithoutTheirPermissions() {
        assertEquals(List.of(), LoreItemsCommandExecutor.topLevelCompletions(player, ""));
    }

    @Test
    void filtersTopLevelRoutesByPermissionAndPrefix() {
        player.addAttachment(plugin, "enthusia.loreitems.admin.create", true);
        player.addAttachment(
                plugin, LoreItemsAdministrationCommandExecutor.AUDIT_PERMISSION, true);

        assertEquals(
                List.of("create", "browse", "anomalies", "audit", "recovery"),
                LoreItemsCommandExecutor.topLevelCompletions(player, ""));
        assertEquals(
                List.of("anomalies", "audit"),
                LoreItemsCommandExecutor.topLevelCompletions(player, "a"));
        assertEquals(
                List.of("browse"),
                LoreItemsCommandExecutor.topLevelCompletions(player, "BR"));
    }
}
