package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.enthusia.loreitems.application.AtomicConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class FoundationConfigurationReloadCommandExecutorTest {
    private ServerMock server;
    private Plugin plugin;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        player = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void permissionGateRejectsWithoutSubmittingReload() {
        AtomicInteger calls = new AtomicInteger();
        FoundationConfigurationReloadCommandExecutor executor =
                new FoundationConfigurationReloadCommandExecutor(plugin, () -> {
                    calls.incrementAndGet();
                    return CompletableFuture.completedFuture(
                            new AtomicConfiguration.ReloadResult(true, "applied"));
                });

        executor.execute(player);

        assertEquals(0, calls.get());
    }

    @Test
    void permittedCommandSubmitsReload() {
        player.addAttachment(
                plugin,
                FoundationConfigurationReloadCommandExecutor.RELOAD_PERMISSION,
                true);
        AtomicInteger calls = new AtomicInteger();
        FoundationConfigurationReloadCommandExecutor executor =
                new FoundationConfigurationReloadCommandExecutor(plugin, () -> {
                    calls.incrementAndGet();
                    return CompletableFuture.completedFuture(
                            new AtomicConfiguration.ReloadResult(true, "applied"));
                });

        executor.execute(player);
        server.getScheduler().performOneTick();

        assertEquals(1, calls.get());
    }

    @Test
    void overlappingReloadsAreCollapsedUntilTheFirstCompletes() {
        player.addAttachment(
                plugin,
                FoundationConfigurationReloadCommandExecutor.RELOAD_PERMISSION,
                true);
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<AtomicConfiguration.ReloadResult> first = new CompletableFuture<>();
        FoundationConfigurationReloadCommandExecutor executor =
                new FoundationConfigurationReloadCommandExecutor(plugin, () -> {
                    calls.incrementAndGet();
                    return first;
                });

        executor.execute(player);
        executor.execute(player);
        assertEquals(1, calls.get());

        first.complete(new AtomicConfiguration.ReloadResult(true, "applied"));
        server.getScheduler().performOneTick();
        executor.execute(player);

        assertEquals(2, calls.get());
    }
}
