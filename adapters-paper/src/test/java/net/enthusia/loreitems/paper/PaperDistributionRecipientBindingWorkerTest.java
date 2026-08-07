package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.enthusia.loreitems.application.DistributionRecipientBindingBatch;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class PaperDistributionRecipientBindingWorkerTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.ofEpochMilli(1_800_000_000_000L), ZoneOffset.UTC);
    private ServerMock server;
    private PaperDistributionRecipientBindingWorker worker;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        if (worker != null) {
            worker.close();
        }
        MockBukkit.unmock();
    }

    @Test
    void javaIdentityBindsUnprefixedNameAndWakesDelivery() {
        UUID playerId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        List<String> lookups = new ArrayList<>();
        List<UUID> wakes = new ArrayList<>();
        worker = worker(
                (id, name, now, limit) -> {
                    lookups.add(name);
                    return CompletableFuture.completedFuture(
                            new DistributionRecipientBindingBatch(1, 1, 0, false));
                },
                wakes,
                1);
        worker.start();

        worker.enqueueIdentity(playerId, "JavaPlayer", false);
        worker.tick();

        assertEquals(List.of("JavaPlayer"), lookups);
        assertEquals(List.of(playerId), wakes);
        assertEquals(0, worker.pendingCount());
        assertEquals(0, worker.inFlightCount());
    }

    @Test
    void floodgateIdentityBindsOnlyExplicitStarKey() {
        UUID playerId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        List<String> lookups = new ArrayList<>();
        worker = worker(
                (id, name, now, limit) -> {
                    lookups.add(name);
                    return CompletableFuture.completedFuture(
                            new DistributionRecipientBindingBatch(1, 1, 0, false));
                },
                new ArrayList<>(),
                1);
        worker.start();

        worker.enqueueIdentity(playerId, "BedrockPlayer", true);
        worker.tick();

        assertEquals(List.of("*BedrockPlayer"), lookups);
    }

    @Test
    void hasMoreRequeuesOneBoundedPageForLaterTick() {
        UUID playerId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        AtomicInteger calls = new AtomicInteger();
        worker = worker(
                (id, name, now, limit) -> CompletableFuture.completedFuture(
                        calls.incrementAndGet() == 1
                                ? new DistributionRecipientBindingBatch(8, 8, 0, true)
                                : new DistributionRecipientBindingBatch(2, 2, 0, false)),
                new ArrayList<>(),
                1);
        worker.start();

        worker.enqueueIdentity(playerId, "LaterPlayer", false);
        worker.tick();
        assertEquals(1, calls.get());
        assertEquals(1, worker.pendingCount());

        worker.tick();
        assertEquals(2, calls.get());
        assertEquals(0, worker.pendingCount());
    }

    @Test
    void inFlightDatabaseWorkNeverExceedsMutationBudget() {
        List<CompletableFuture<DistributionRecipientBindingBatch>> futures = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        worker = worker(
                (id, name, now, limit) -> {
                    calls.incrementAndGet();
                    CompletableFuture<DistributionRecipientBindingBatch> future = new CompletableFuture<>();
                    futures.add(future);
                    return future;
                },
                new ArrayList<>(),
                2);
        worker.start();
        for (int index = 0; index < 10; index++) {
            worker.enqueueIdentity(
                    new UUID(0L, index + 1L),
                    "Player" + index,
                    false);
        }

        worker.tick();

        assertEquals(2, calls.get());
        assertEquals(2, worker.inFlightCount());
        assertEquals(8, worker.pendingCount());
        futures.get(0).complete(new DistributionRecipientBindingBatch(0, 0, 0, false));
        futures.get(1).complete(new DistributionRecipientBindingBatch(0, 0, 0, false));
        assertEquals(0, worker.inFlightCount());

        worker.tick();
        assertEquals(4, calls.get());
        assertTrue(worker.inFlightCount() <= 2);
    }

    private PaperDistributionRecipientBindingWorker worker(
            PaperDistributionRecipientBindingWorker.BindingFunction binding,
            List<UUID> wakes,
            int budget) {
        Plugin plugin = MockBukkit.createMockPlugin();
        return new PaperDistributionRecipientBindingWorker(
                plugin,
                binding,
                wakes::add,
                ignored -> false,
                CLOCK,
                8,
                budget);
    }
}
