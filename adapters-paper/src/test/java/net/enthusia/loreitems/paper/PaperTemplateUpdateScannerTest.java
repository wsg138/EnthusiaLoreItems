package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class PaperTemplateUpdateScannerTest {
    private static final LoreItemIdentity TARGET_IDENTITY = new LoreItemIdentity(
            new LoreDefinitionId(
                    UUID.fromString("11111111-1111-1111-1111-111111111111")),
            new LoreInstanceId(
                    UUID.fromString("22222222-2222-2222-2222-222222222222")),
            new TemplateRevision(1L));

    private PlayerMock player;
    private Plugin plugin;

    @BeforeEach
    void setUp() {
        ServerMock server = MockBukkit.mock();
        player = server.addPlayer();
        plugin = MockBukkit.createMockPlugin();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void continuationFindsALateNestedItemWithoutSubmittingAPartialScan() {
        populateShulkers(false);
        PaperTemplateUpdateScanner scanner = new PaperTemplateUpdateScanner();
        List<PaperTemplateUpdateScanner.Candidate> candidates = new ArrayList<>();

        PaperTemplateUpdateScanner.ScanResult first = scanner.scan(
                plugin, player.getInventory(), candidates::add);
        assertTrue(first.continuationRequired());
        assertFalse(first.abandoned());
        assertTrue(candidates.isEmpty());

        PaperTemplateUpdateScanner.ScanResult second = scanner.scan(
                plugin, player.getInventory(), candidates::add);
        assertFalse(second.continuationRequired());
        assertFalse(second.abandoned());
        assertEquals(1, second.submitted());
        assertEquals(List.of(TARGET_IDENTITY), candidates.stream()
                .map(PaperTemplateUpdateScanner.Candidate::identity)
                .toList());
    }

    @Test
    void duplicateIdentitiesAcrossContinuationPassesAreNotSubmitted() {
        populateShulkers(true);
        PaperTemplateUpdateScanner scanner = new PaperTemplateUpdateScanner();
        List<PaperTemplateUpdateScanner.Candidate> candidates = new ArrayList<>();

        PaperTemplateUpdateScanner.ScanResult first = scanner.scan(
                plugin, player.getInventory(), candidates::add);
        PaperTemplateUpdateScanner.ScanResult second = scanner.scan(
                plugin, player.getInventory(), candidates::add);

        assertTrue(first.continuationRequired());
        assertFalse(second.continuationRequired());
        assertEquals(0, second.submitted());
        assertTrue(candidates.isEmpty());
    }

    private void populateShulkers(boolean includeEarlyDuplicate) {
        PaperItemIdentityCodec identityCodec = new PaperItemIdentityCodec();
        for (int rootSlot = 0; rootSlot < 10; rootSlot++) {
            ItemStack shulkerItem = ItemStack.of(Material.SHULKER_BOX);
            BlockStateMeta meta = (BlockStateMeta) shulkerItem.getItemMeta();
            ShulkerBox shulker = (ShulkerBox) meta.getBlockState();
            for (int nestedSlot = 0; nestedSlot < shulker.getInventory().getSize(); nestedSlot++) {
                ItemStack nested = ItemStack.of(Material.COBBLESTONE);
                boolean lateTarget = rootSlot == 9 && nestedSlot == 26;
                boolean earlyDuplicate = includeEarlyDuplicate
                        && rootSlot == 0
                        && nestedSlot == 0;
                if (lateTarget || earlyDuplicate) {
                    nested = identityCodec.writeIdentity(nested, TARGET_IDENTITY);
                }
                shulker.getInventory().setItem(nestedSlot, nested);
            }
            meta.setBlockState(shulker);
            assertTrue(shulkerItem.setItemMeta(meta));
            player.getInventory().setItem(rootSlot, shulkerItem);
        }
    }
}
