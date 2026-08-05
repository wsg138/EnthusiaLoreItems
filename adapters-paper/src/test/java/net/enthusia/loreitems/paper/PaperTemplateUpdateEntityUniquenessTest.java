package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
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

class PaperTemplateUpdateEntityUniquenessTest {
    private static final LoreItemIdentity IDENTITY = new LoreItemIdentity(
            new LoreDefinitionId(UUID.fromString(
                    "11111111-1111-1111-1111-111111111111")),
            new LoreInstanceId(UUID.fromString(
                    "22222222-2222-2222-2222-222222222222")),
            new TemplateRevision(1));
    private static final UUID ENTITY_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    private ServerMock server;
    private PlayerMock player;
    private PaperTemplateUpdateAccessRegistry registry;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer();
        registry = new PaperTemplateUpdateAccessRegistry();
        registry.replace(
                new PaperInventoryReference.PlayerMain(player.getUniqueId()), List.of());
        registry.replace(
                new PaperInventoryReference.PlayerEnder(player.getUniqueId()), List.of());
        registry.drainUnique(server.getOnlinePlayers());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void incompleteLoadedEntityCoverageBlocksInventoryCandidates() {
        PaperInventoryReference.PlayerMain main =
                new PaperInventoryReference.PlayerMain(player.getUniqueId());
        registry.replace(main, List.of(inventoryCandidate(main)));
        registry.markEntityCoverageIncomplete();

        assertTrue(registry.drainUnique(server.getOnlinePlayers()).isEmpty());

        registry.completeEntityCoverage(Set.of());
        assertEquals(1, registry.drainUnique(server.getOnlinePlayers()).size());
    }

    @Test
    void suppressesTheSameInstanceAcrossInventoryAndLoadedEntitySnapshots() {
        PaperInventoryReference.PlayerMain main =
                new PaperInventoryReference.PlayerMain(player.getUniqueId());
        registry.replace(main, List.of(inventoryCandidate(main)));
        registry.markEntityCoverageIncomplete();
        registry.replaceEntity(ENTITY_ID, entityCandidate());
        registry.completeEntityCoverage(Set.of(ENTITY_ID));

        assertTrue(registry.drainUnique(server.getOnlinePlayers()).isEmpty());
    }

    @Test
    void rejectedEntityCandidateRemainsEligibleForBoundedRetry() {
        registry.markEntityCoverageIncomplete();
        registry.replaceEntity(ENTITY_ID, entityCandidate());
        registry.completeEntityCoverage(Set.of(ENTITY_ID));
        PaperTemplateUpdateAccessRegistry.DispatchBatch first =
                registry.prepareDispatch(server.getOnlinePlayers());
        assertEquals(1, first.candidates().size());

        registry.finishDispatch(first, Set.of(IDENTITY.instanceId().value()));

        PaperTemplateUpdateAccessRegistry.DispatchBatch retried =
                registry.prepareDispatch(server.getOnlinePlayers());
        assertEquals(1, retried.candidates().size());
        registry.finishDispatch(retried, Set.of());
    }

    private static PaperTemplateUpdateScanner.Candidate inventoryCandidate(
            PaperInventoryReference reference) {
        return new PaperTemplateUpdateScanner.Candidate(
                IDENTITY,
                PaperTemplateUpdateItemReference.root(reference, 0));
    }

    private static PaperTemplateUpdateScanner.Candidate entityCandidate() {
        return new PaperTemplateUpdateScanner.Candidate(
                IDENTITY,
                new PaperEntityTemplateUpdateReference(
                        ENTITY_ID,
                        PaperEntityTemplateUpdateReference.Kind.DROPPED_ITEM));
    }
}
