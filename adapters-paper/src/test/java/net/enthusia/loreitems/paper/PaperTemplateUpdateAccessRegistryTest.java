package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

@SuppressWarnings("auxiliaryclass")
class PaperTemplateUpdateAccessRegistryTest {
    private static final LoreItemIdentity IDENTITY = new LoreItemIdentity(
            new LoreDefinitionId(
                    UUID.fromString("11111111-1111-1111-1111-111111111111")),
            new LoreInstanceId(
                    UUID.fromString("22222222-2222-2222-2222-222222222222")),
            new TemplateRevision(1L));

    private ServerMock server;
    private PlayerMock firstPlayer;
    private PlayerMock secondPlayer;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        firstPlayer = server.addPlayer();
        secondPlayer = server.addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void doesNotReleaseACandidateUntilEveryOnlinePlayerInventoryIsCovered() {
        PaperTemplateUpdateAccessRegistry registry = new PaperTemplateUpdateAccessRegistry();
        PaperInventoryReference.PlayerMain firstMain =
                new PaperInventoryReference.PlayerMain(firstPlayer.getUniqueId());
        registry.replace(firstMain, List.of(candidate(firstMain, 0)));
        registry.replace(
                new PaperInventoryReference.PlayerEnder(firstPlayer.getUniqueId()), List.of());

        assertTrue(registry.drainUnique(server.getOnlinePlayers()).isEmpty());

        registry.replace(
                new PaperInventoryReference.PlayerMain(secondPlayer.getUniqueId()), List.of());
        registry.replace(
                new PaperInventoryReference.PlayerEnder(secondPlayer.getUniqueId()), List.of());
        assertEquals(1, registry.drainUnique(server.getOnlinePlayers()).size());
    }

    @Test
    void suppressesTheSameInstanceAcrossSeparatePlayerInventories() {
        PaperTemplateUpdateAccessRegistry registry = coveredRegistry();
        PaperInventoryReference.PlayerMain firstMain =
                new PaperInventoryReference.PlayerMain(firstPlayer.getUniqueId());
        PaperInventoryReference.PlayerEnder secondEnder =
                new PaperInventoryReference.PlayerEnder(secondPlayer.getUniqueId());
        registry.replace(firstMain, List.of(candidate(firstMain, 0)));
        registry.replace(secondEnder, List.of(candidate(secondEnder, 1)));

        assertTrue(registry.drainUnique(server.getOnlinePlayers()).isEmpty());
    }

    @Test
    void incompleteTransientCoverageFailsClosedUntilThatReferenceIsResolved() {
        PaperTemplateUpdateAccessRegistry registry = coveredRegistry();
        PaperInventoryReference.PlayerMain firstMain =
                new PaperInventoryReference.PlayerMain(firstPlayer.getUniqueId());
        PaperInventoryReference.Block transientReference =
                new PaperInventoryReference.Block(UUID.randomUUID(), 1, 2, 3);
        registry.replace(firstMain, List.of(candidate(firstMain, 0)));
        registry.markIncomplete(transientReference);

        assertTrue(registry.drainUnique(server.getOnlinePlayers()).isEmpty());

        registry.remove(transientReference);
        assertEquals(1, registry.drainUnique(server.getOnlinePlayers()).size());
    }

    @Test
    void coordinatorRejectionRequeuesTheInventoryReferenceForNaturalRetry() {
        PaperInventoryReference reference = blockReference(7);
        PaperTemplateUpdateScanner.Candidate candidate = candidate(reference, 2);
        List<PaperInventoryReference> retried = new ArrayList<>();

        PaperTemplateUpdateListener.dispatchCandidates(
                List.of(candidate),
                ignored -> false,
                retried::add);

        assertEquals(List.of(reference), retried);
    }

    @Test
    void rejectedScanReferencesAreRetriedOnceInFifoOrder() {
        PaperTemplateUpdateRetryBacklog retries = new PaperTemplateUpdateRetryBacklog(2);
        PaperInventoryReference first = blockReference(8);
        PaperInventoryReference second = blockReference(9);
        PaperInventoryReference overflow = blockReference(10);

        assertTrue(retries.offer(first));
        assertFalse(retries.offer(first));
        assertTrue(retries.offer(second));
        assertFalse(retries.offer(overflow));
        assertEquals(first, retries.poll());
        assertEquals(second, retries.poll());
        assertNull(retries.poll());
    }

    @Test
    void scanBacklogRetainsOneBoundedOverflowTierInFifoOrder() {
        PaperTemplateUpdateScanBacklog backlog = new PaperTemplateUpdateScanBacklog(2);
        PaperInventoryReference first = blockReference(1);
        PaperInventoryReference second = blockReference(2);
        PaperInventoryReference third = blockReference(3);
        PaperInventoryReference fourth = blockReference(4);
        PaperInventoryReference rejected = blockReference(5);

        assertEquals(PaperTemplateUpdateScanBacklog.OfferResult.READY, backlog.offer(first));
        assertEquals(PaperTemplateUpdateScanBacklog.OfferResult.READY, backlog.offer(second));
        assertEquals(PaperTemplateUpdateScanBacklog.OfferResult.DEFERRED, backlog.offer(third));
        assertEquals(PaperTemplateUpdateScanBacklog.OfferResult.DEFERRED, backlog.offer(fourth));
        assertEquals(
                PaperTemplateUpdateScanBacklog.OfferResult.ALREADY_QUEUED,
                backlog.offer(third));
        assertEquals(PaperTemplateUpdateScanBacklog.OfferResult.REJECTED, backlog.offer(rejected));

        assertEquals(first, backlog.poll());
        assertEquals(second, backlog.poll());
        assertEquals(third, backlog.poll());
        assertEquals(fourth, backlog.poll());
        assertTrue(backlog.isEmpty());
    }

    @Test
    void removingAQueuedReferencePromotesDeferredWorkAndAllowsRequeue() {
        PaperTemplateUpdateScanBacklog backlog = new PaperTemplateUpdateScanBacklog(1);
        PaperInventoryReference first = blockReference(1);
        PaperInventoryReference deferred = blockReference(2);

        assertEquals(PaperTemplateUpdateScanBacklog.OfferResult.READY, backlog.offer(first));
        assertEquals(
                PaperTemplateUpdateScanBacklog.OfferResult.DEFERRED,
                backlog.offer(deferred));
        backlog.remove(first);

        assertEquals(deferred, backlog.poll());
        assertEquals(PaperTemplateUpdateScanBacklog.OfferResult.READY, backlog.offer(first));
        assertEquals(first, backlog.poll());
        assertTrue(backlog.isEmpty());
    }

    private PaperTemplateUpdateAccessRegistry coveredRegistry() {
        PaperTemplateUpdateAccessRegistry registry = new PaperTemplateUpdateAccessRegistry();
        registry.replace(
                new PaperInventoryReference.PlayerMain(firstPlayer.getUniqueId()), List.of());
        registry.replace(
                new PaperInventoryReference.PlayerEnder(firstPlayer.getUniqueId()), List.of());
        registry.replace(
                new PaperInventoryReference.PlayerMain(secondPlayer.getUniqueId()), List.of());
        registry.replace(
                new PaperInventoryReference.PlayerEnder(secondPlayer.getUniqueId()), List.of());
        registry.drainUnique(server.getOnlinePlayers());
        return registry;
    }

    private static PaperTemplateUpdateScanner.Candidate candidate(
            PaperInventoryReference reference,
            int slot) {
        return new PaperTemplateUpdateScanner.Candidate(
                IDENTITY,
                PaperTemplateUpdateItemReference.root(reference, slot));
    }

    private static PaperInventoryReference.Block blockReference(int coordinate) {
        return new PaperInventoryReference.Block(
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                coordinate,
                coordinate,
                coordinate);
    }
}
