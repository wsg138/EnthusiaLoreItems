package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
