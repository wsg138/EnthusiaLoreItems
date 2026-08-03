package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.enthusia.loreitems.application.ItemCodecException;
import net.enthusia.loreitems.application.ItemIdentityReadResult;
import net.enthusia.loreitems.application.PrepareHeldItemAdoptionRequest;
import net.enthusia.loreitems.application.PreparedHeldItemAdoption;
import net.enthusia.loreitems.domain.DefinitionKey;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class PaperHeldItemAdoptionOperatorTest {
    private static final int SLOT = 3;
    private static final NamespacedKey FOREIGN_KEY = Objects.requireNonNull(
            NamespacedKey.fromString("test:foreign-adoption"));

    private ServerMock server;
    private PlayerMock player;
    private PaperHeldItemAdoptionOperator operator;
    private PaperItemIdentityCodec identityCodec;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer();
        operator = new PaperHeldItemAdoptionOperator();
        identityCodec = new PaperItemIdentityCodec();
        player.getInventory().setHeldItemSlot(SLOT);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void preservesVisibleAndForeignDataWhileAssigningFreshIdentity() {
        ItemStack original = namedSword();
        player.getInventory().setItem(SLOT, original);
        DefinitionKey key = new DefinitionKey("vanguards_blade");
        PrepareHeldItemAdoptionRequest request = operator.snapshot(player, key);
        PreparedHeldItemAdoption adoption = prepared(request);

        PaperHeldItemAdoptionOperator.ApplyResult result = operator.apply(player, adoption);

        assertEquals(PaperHeldItemAdoptionOperator.ApplyResult.Status.APPLIED, result.status());
        assertEquals(64, result.afterFingerprint().length());
        ItemStack stored = Objects.requireNonNull(player.getInventory().getItem(SLOT));
        assertEquals(1, stored.getAmount());
        assertEquals(1, stored.getMaxStackSize());
        ItemMeta meta = Objects.requireNonNull(stored.getItemMeta());
        assertEquals(Component.text("Original blade"), meta.displayName());
        assertEquals(List.of(Component.text("Visible lore remains")), meta.lore());
        assertEquals("kept", meta.getPersistentDataContainer().get(
                FOREIGN_KEY, PersistentDataType.STRING));
        ItemIdentityReadResult.Tracked tracked = assertInstanceOf(
                ItemIdentityReadResult.Tracked.class,
                identityCodec.readIdentity(stored));
        assertEquals(adoption.identity(), tracked.identity());
    }

    @Test
    void changedExactSlotEntersReviewWithoutOverwritingNewItem() {
        player.getInventory().setItem(SLOT, namedSword());
        PrepareHeldItemAdoptionRequest request = operator.snapshot(
                player, new DefinitionKey("vanguards_blade"));
        PreparedHeldItemAdoption adoption = prepared(request);
        ItemStack replacement = ItemStack.of(Material.DIAMOND);
        player.getInventory().setItem(SLOT, replacement);

        PaperHeldItemAdoptionOperator.ApplyResult result = operator.apply(player, adoption);

        assertEquals(
                PaperHeldItemAdoptionOperator.ApplyResult.Status.REVIEW_REQUIRED,
                result.status());
        ItemStack stored = Objects.requireNonNull(player.getInventory().getItem(SLOT));
        assertEquals(Material.DIAMOND, stored.getType());
        assertInstanceOf(
                ItemIdentityReadResult.Untracked.class,
                identityCodec.readIdentity(stored));
    }

    @Test
    void stackedAndAlreadyTrackedItemsArePreservedAndRejected() {
        ItemStack stacked = ItemStack.of(Material.PAPER, 2);
        player.getInventory().setItem(SLOT, stacked);
        assertThrows(ItemCodecException.class, () -> operator.snapshot(
                player, new DefinitionKey("stacked")));
        assertEquals(2, player.getInventory().getItem(SLOT).getAmount());

        ItemStack tracked = identityCodec.writeIdentity(
                ItemStack.of(Material.PAPER),
                preparedIdentity());
        player.getInventory().setItem(SLOT, tracked);
        assertThrows(ItemCodecException.class, () -> operator.snapshot(
                player, new DefinitionKey("already_tracked")));
        assertTrue(identityCodec.readIdentity(player.getInventory().getItem(SLOT))
                instanceof ItemIdentityReadResult.Tracked);
    }

    private static ItemStack namedSword() {
        ItemStack item = ItemStack.of(Material.DIAMOND_SWORD);
        ItemMeta meta = Objects.requireNonNull(item.getItemMeta());
        meta.displayName(Component.text("Original blade"));
        meta.lore(List.of(Component.text("Visible lore remains")));
        meta.getPersistentDataContainer().set(
                FOREIGN_KEY, PersistentDataType.STRING, "kept");
        assertTrue(item.setItemMeta(meta));
        return item;
    }

    private static PreparedHeldItemAdoption prepared(
            PrepareHeldItemAdoptionRequest request) {
        return new PreparedHeldItemAdoption(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                request.definitionKey(),
                new LoreDefinitionId(UUID.fromString(
                        "22222222-2222-2222-2222-222222222222")),
                new LoreInstanceId(UUID.fromString(
                        "33333333-3333-3333-3333-333333333333")),
                new TemplateRevision(4),
                request.playerId(),
                request.selectedSlot(),
                request.beforeFingerprint(),
                UUID.fromString("44444444-4444-4444-4444-444444444444"),
                1_000L,
                31_000L);
    }

    private static net.enthusia.loreitems.application.LoreItemIdentity preparedIdentity() {
        return new net.enthusia.loreitems.application.LoreItemIdentity(
                new LoreDefinitionId(UUID.fromString(
                        "55555555-5555-5555-5555-555555555555")),
                new LoreInstanceId(UUID.fromString(
                        "66666666-6666-6666-6666-666666666666")),
                new TemplateRevision(1));
    }
}
