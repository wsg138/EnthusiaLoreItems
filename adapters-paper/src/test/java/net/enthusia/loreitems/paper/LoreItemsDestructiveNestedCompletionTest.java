package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class LoreItemsDestructiveNestedCompletionTest {
    private Plugin plugin;
    private PlayerMock player;
    private LoreItemsDestructiveCommandExecutor executor;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        player = MockBukkit.getMock().addPlayer();
        executor = new LoreItemsDestructiveCommandExecutor(
                plugin,
                () -> null,
                () -> 50,
                () -> {});
    }

    @AfterEach
    void tearDown() {
        executor.close();
        MockBukkit.unmock();
    }

    @Test
    void hidesReviewResolutionCompletionsWithoutReviewPermission() {
        assertEquals(
                List.of(),
                executor.onTabComplete(
                        player,
                        command(),
                        "loreitems",
                        new String[] {"resolve-removal", "operation", "instance", ""}));
    }

    @Test
    void exposesReviewResolutionCompletionsWithReviewPermission() {
        player.addAttachment(
                plugin, LoreItemsDestructiveCommandExecutor.REVIEW_PERMISSION, true);

        assertEquals(
                List.of("requeue", "removed", "abort"),
                executor.onTabComplete(
                        player,
                        command(),
                        "loreitems",
                        new String[] {"resolve-removal", "operation", "instance", ""}));
    }

    private static Command command() {
        return new Command("loreitems") {
            @Override
            public boolean execute(
                    CommandSender sender, String commandLabel, String[] arguments) {
                throw new AssertionError("command must not execute");
            }
        };
    }
}
