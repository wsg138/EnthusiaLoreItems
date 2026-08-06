package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.UUID;
import net.enthusia.loreitems.application.DestructiveRemovalExecutionUseCase;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.domain.DestructiveOperationType;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class PaperDestructiveRemovalOperatorTest {
    private static final LoreDefinitionId DEFINITION_ID = new LoreDefinitionId(
            UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final LoreInstanceId INSTANCE_ID = new LoreInstanceId(
            UUID.fromString("22222222-2222-2222-2222-222222222222"));
    private static final LoreItemIdentity IDENTITY =
            new LoreItemIdentity(DEFINITION_ID, INSTANCE_ID, new TemplateRevision(3L));

    private PlayerMock player;
    private Plugin plugin;
    private PaperItemIdentityCodec identityCodec;
    private PaperDestructiveRemovalOperator operator;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        player = MockBukkit.getMock().addPlayer();
        plugin = MockBukkit.createMockPlugin();
        identityCodec = new PaperItemIdentityCodec();
        operator = new PaperDestructiveRemovalOperator(identityCodec);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void observesAndRemovesTheSameInventoryReference() {
        ItemStack tracked = identityCodec.writeIdentity(
                ItemStack.of(Material.DIAMOND_SWORD), IDENTITY);
        player.getInventory().setItem(0, tracked);
        PaperTemplateUpdateScanner.Candidate candidate = candidate();

        PaperDestructiveRemovalOperator.ObservationResult observation =
                operator.observe(plugin, candidate);

        assertEquals(
                PaperDestructiveRemovalOperator.ObservationResult.Status.OBSERVED,
                observation.status());
        DestructiveRemovalExecutionUseCase.Observation evidence = observation.observation();
        assertEquals("PLAYER_INVENTORY", evidence.locationType());
        assertEquals(player.getUniqueId().toString(), evidence.locationKey());
        assertEquals("slot=0", evidence.containerPath());

        DestructiveRemovalExecutionUseCase.PreparedRemoval prepared =
                prepared(evidence, Instant.now().plusSeconds(30));
        PaperDestructiveRemovalOperator.ApplyResult result =
                operator.remove(plugin, candidate.reference(), prepared);

        assertEquals(
                PaperDestructiveRemovalOperator.ApplyResult.Status.REMOVED,
                result.status());
        assertEquals(evidence.fingerprint(), result.beforeFingerprint());
        assertNull(player.getInventory().getItem(0));
    }

    @Test
    void refusesToRemoveWhenThePhysicalItemChangedAfterPreparation() {
        ItemStack tracked = identityCodec.writeIdentity(
                ItemStack.of(Material.DIAMOND_SWORD), IDENTITY);
        player.getInventory().setItem(0, tracked);
        PaperTemplateUpdateScanner.Candidate candidate = candidate();
        DestructiveRemovalExecutionUseCase.Observation evidence =
                operator.observe(plugin, candidate).observation();
        player.getInventory().setItem(0, ItemStack.of(Material.STONE));

        PaperDestructiveRemovalOperator.ApplyResult result = operator.remove(
                plugin,
                candidate.reference(),
                prepared(evidence, Instant.now().plusSeconds(30)));

        assertEquals(
                PaperDestructiveRemovalOperator.ApplyResult.Status.REVIEW_REQUIRED,
                result.status());
        ItemStack remaining = player.getInventory().getItem(0);
        assertNotNull(remaining);
        assertEquals(Material.STONE, remaining.getType());
    }

    private PaperTemplateUpdateScanner.Candidate candidate() {
        return new PaperTemplateUpdateScanner.Candidate(
                IDENTITY,
                PaperTemplateUpdateItemReference.root(
                        new PaperInventoryReference.PlayerMain(player.getUniqueId()),
                        0));
    }

    private static DestructiveRemovalExecutionUseCase.PreparedRemoval prepared(
            DestructiveRemovalExecutionUseCase.Observation observation,
            Instant expiry) {
        return new DestructiveRemovalExecutionUseCase.PreparedRemoval(
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                DestructiveOperationType.EXACT_INSTANCE_REMOVAL,
                DEFINITION_ID,
                INSTANCE_ID,
                IDENTITY.appliedRevision(),
                IDENTITY,
                observation.locationType(),
                observation.locationKey(),
                observation.containerPath(),
                observation.fingerprint(),
                "claim-token",
                expiry.toEpochMilli());
    }
}
