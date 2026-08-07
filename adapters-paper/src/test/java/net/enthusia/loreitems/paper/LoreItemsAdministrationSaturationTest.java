package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.AuditEventRecord;
import net.enthusia.loreitems.application.LoreItemsAdministrationUseCase;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.domain.InstanceAnomaly;
import net.enthusia.loreitems.domain.InstanceCurrentState;
import net.enthusia.loreitems.domain.InstanceObservation;
import net.enthusia.loreitems.domain.LoreInstanceId;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class LoreItemsAdministrationSaturationTest {
    private ServerMock server;
    private Plugin plugin;
    private LoreItemsAdministrationCommandExecutor executor;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.close();
        }
        MockBukkit.unmock();
    }

    @Test
    void administrationQueriesCapAtThirtyTwoAndReleaseCapacityOnCompletion() {
        BlockingUseCase useCase = new BlockingUseCase();
        server.getServicesManager().register(
                LoreItemsAdministrationUseCase.class,
                useCase,
                plugin,
                ServicePriority.Normal);
        executor = new LoreItemsAdministrationCommandExecutor(plugin, 200);
        List<PlayerMock> players = new ArrayList<>();
        for (int index = 0; index < 33; index++) {
            PlayerMock player = server.addPlayer();
            player.addAttachment(
                    plugin,
                    LoreItemsAdministrationCommandExecutor.AUDIT_PERMISSION,
                    true);
            players.add(player);
        }

        for (PlayerMock player : players) {
            executor.onCommand(player, command(), "loreitems", new String[] {"anomalies"});
        }

        assertEquals(32, useCase.queries.size());
        useCase.queries.get(0).complete(new Page<>(List.of(), 0, 200, false));
        executor.onCommand(
                players.get(32),
                command(),
                "loreitems",
                new String[] {"anomalies"});
        assertEquals(33, useCase.queries.size());
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

    private static final class BlockingUseCase implements LoreItemsAdministrationUseCase {
        private final List<CompletableFuture<Page<InstanceAnomaly>>> queries = new ArrayList<>();

        @Override
        public CompletionStage<Page<InstanceAnomaly>> listActiveAnomalies(PageRequest request) {
            CompletableFuture<Page<InstanceAnomaly>> query = new CompletableFuture<>();
            queries.add(query);
            return query;
        }

        @Override
        public CompletionStage<Page<InstanceAnomaly>> listWarningAnomalies(PageRequest request) {
            return unsupported();
        }

        @Override
        public CompletionStage<Optional<InstanceCurrentState>> findCurrentState(
                LoreInstanceId instanceId) {
            return unsupported();
        }

        @Override
        public CompletionStage<Page<InstanceObservation>> listInstanceObservations(
                LoreInstanceId instanceId,
                PageRequest request) {
            return unsupported();
        }

        @Override
        public CompletionStage<Page<InstanceAnomaly>> listInstanceAnomalies(
                LoreInstanceId instanceId,
                PageRequest request) {
            return unsupported();
        }

        @Override
        public CompletionStage<Page<AuditEventRecord>> listInstanceAudit(
                LoreInstanceId instanceId,
                PageRequest request) {
            return unsupported();
        }

        @Override
        public CompletionStage<RecoveryPage> listRecovery(PageRequest request) {
            return unsupported();
        }

        private static <T> CompletionStage<T> unsupported() {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }
    }
}
