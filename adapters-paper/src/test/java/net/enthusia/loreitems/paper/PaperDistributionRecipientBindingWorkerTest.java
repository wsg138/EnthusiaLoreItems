package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.enthusia.loreitems.application.BindDistributionRecipientsUseCase;
import net.enthusia.loreitems.application.DistributionRecipientBindingBatch;
import net.enthusia.loreitems.application.DistributionRecipientRepository;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class PaperDistributionRecipientBindingWorkerTest {
    private static final Clock CLOCK = Clock.fixed(
            Instant.ofEpochMilli(1_800_000_000_000L), ZoneOffset.UTC);
    private static final List<UUID> BUDGET_TEST_PLAYERS = List.of(
            new UUID(0L, 1L),
            new UUID(0L, 2L),
            new UUID(0L, 3L),
            new UUID(0L, 4L),
            new UUID(0L, 5L),
            new UUID(0L, 6L),
            new UUID(0L, 7L),
            new UUID(0L, 8L),
            new UUID(0L, 9L),
            new UUID(0L, 10L));

    private PaperDistributionRecipientBindingWorker workerUnderTest;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        if (workerUnderTest != null) {
            workerUnderTest.close();
        }
        MockBukkit.unmock();
    }

    @Test
    void runtimeConstructionDoesNotTouchBukkitBeforeMainThreadActivation() {
        Plugin plugin = throwingProxy(Plugin.class);
        DistributionRecipientRepository repository = throwingProxy(DistributionRecipientRepository.class);
        BindDistributionRecipientsUseCase useCase = new BindDistributionRecipientsUseCase(repository);

        assertDoesNotThrow(() -> new PaperDistributionRecipientBindingWorker(
                plugin,
                useCase,
                ignored -> {},
                8,
                1));
    }

    @Test
    void javaIdentityBindsUnprefixedNameAndWakesDelivery() {
        UUID playerId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        List<String> lookups = new ArrayList<>();
        List<UUID> wakes = new ArrayList<>();
        workerUnderTest = newWorker(
                (id, name, now, limit) -> {
                    lookups.add(name);
                    return CompletableFuture.completedFuture(
                            new DistributionRecipientBindingBatch(1, 1, 0, false));
                },
                wakes,
                1);
        workerUnderTest.start();

        workerUnderTest.enqueueIdentity(playerId, "JavaPlayer", false);
        workerUnderTest.tick();

        assertEquals(List.of("JavaPlayer"), lookups);
        assertEquals(List.of(playerId), wakes);
        assertEquals(0, workerUnderTest.pendingCount());
        assertEquals(0, workerUnderTest.inFlightCount());
    }

    @Test
    void floodgateIdentityBindsOnlyExplicitStarKey() {
        UUID playerId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        List<String> lookups = new ArrayList<>();
        workerUnderTest = newWorker(
                (id, name, now, limit) -> {
                    lookups.add(name);
                    return CompletableFuture.completedFuture(
                            new DistributionRecipientBindingBatch(1, 1, 0, false));
                },
                new ArrayList<>(),
                1);
        workerUnderTest.start();

        workerUnderTest.enqueueIdentity(playerId, "BedrockPlayer", true);
        workerUnderTest.tick();

        assertEquals(List.of("*BedrockPlayer"), lookups);
    }

    @Test
    void floodgateServerVisiblePrefixedNameIsPreservedWithoutDoublePrefix() {
        UUID playerId = UUID.fromString("22222222-2222-2222-2222-222222222223");
        List<String> lookups = new ArrayList<>();
        workerUnderTest = newWorker(
                (id, name, now, limit) -> {
                    lookups.add(name);
                    return CompletableFuture.completedFuture(
                            new DistributionRecipientBindingBatch(1, 1, 0, false));
                },
                new ArrayList<>(),
                1);
        workerUnderTest.start();

        workerUnderTest.enqueueIdentity(playerId, "*BedrockPlayer", true);
        workerUnderTest.tick();

        assertEquals(List.of("*BedrockPlayer"), lookups);
    }

    @Test
    void hasMoreRequeuesOneBoundedPageForLaterTick() {
        UUID playerId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        AtomicInteger calls = new AtomicInteger();
        workerUnderTest = newWorker(
                (id, name, now, limit) -> CompletableFuture.completedFuture(
                        calls.incrementAndGet() == 1
                                ? new DistributionRecipientBindingBatch(8, 8, 0, true)
                                : new DistributionRecipientBindingBatch(2, 2, 0, false)),
                new ArrayList<>(),
                1);
        workerUnderTest.start();

        workerUnderTest.enqueueIdentity(playerId, "LaterPlayer", false);
        workerUnderTest.tick();
        assertEquals(1, calls.get());
        assertEquals(1, workerUnderTest.pendingCount());

        workerUnderTest.tick();
        assertEquals(2, calls.get());
        assertEquals(0, workerUnderTest.pendingCount());
    }

    @Test
    void inFlightDatabaseWorkNeverExceedsMutationBudget() {
        List<CompletableFuture<DistributionRecipientBindingBatch>> futures = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        workerUnderTest = newWorker(
                (id, name, now, limit) -> {
                    calls.incrementAndGet();
                    CompletableFuture<DistributionRecipientBindingBatch> future = new CompletableFuture<>();
                    futures.add(future);
                    return future;
                },
                new ArrayList<>(),
                2);
        workerUnderTest.start();
        int playerNumber = 0;
        for (UUID playerId : BUDGET_TEST_PLAYERS) {
            workerUnderTest.enqueueIdentity(playerId, "Player" + playerNumber, false);
            playerNumber++;
        }

        workerUnderTest.tick();

        assertEquals(2, calls.get());
        assertEquals(2, workerUnderTest.inFlightCount());
        assertEquals(8, workerUnderTest.pendingCount());
        futures.get(0).complete(new DistributionRecipientBindingBatch(0, 0, 0, false));
        futures.get(1).complete(new DistributionRecipientBindingBatch(0, 0, 0, false));
        assertEquals(0, workerUnderTest.inFlightCount());

        workerUnderTest.tick();
        assertEquals(4, calls.get());
        assertTrue(workerUnderTest.inFlightCount() <= 2);
    }

    private PaperDistributionRecipientBindingWorker newWorker(
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

    private static <T> T throwingProxy(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[] {type},
                (proxy, method, arguments) -> {
                    throw new AssertionError(
                            "Construction must not call " + type.getSimpleName() + '.' + method.getName());
                }));
    }
}
