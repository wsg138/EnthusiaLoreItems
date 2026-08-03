package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.enthusia.loreitems.application.EncodedItemTemplate;
import net.enthusia.loreitems.application.ItemIdentityReadResult;
import net.enthusia.loreitems.application.PreparedDirectDelivery;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class PaperDirectDeliveryOperatorTest {
    private PlayerMock player;
    private PaperDirectDeliveryOperator operator;
    private PaperItemTemplateCodec templateCodec;
    private PaperItemIdentityCodec identityCodec;

    @BeforeEach
    void setUp() {
        ServerMock server = MockBukkit.mock();
        player = server.addPlayer();
        operator = new PaperDirectDeliveryOperator();
        templateCodec = new PaperItemTemplateCodec();
        identityCodec = new PaperItemIdentityCodec();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void insertsOneVerifiedInstanceIntoAnExactEmptyStorageSlot() {
        ItemStack template = ItemStack.of(Material.DIAMOND_SWORD);
        ItemMeta meta = Objects.requireNonNull(template.getItemMeta());
        meta.displayName(Component.text("Queued blade"));
        meta.lore(List.of(Component.text("Delivered safely")));
        assertTrue(template.setItemMeta(meta));
        PreparedDirectDelivery delivery = prepared(templateCodec.encode(template));

        PaperDirectDeliveryOperator.ApplyResult result = operator.apply(player, delivery);

        assertEquals(PaperDirectDeliveryOperator.ApplyResult.Status.APPLIED, result.status());
        assertEquals(64, result.afterFingerprint().length());
        ItemStack stored = Objects.requireNonNull(
                player.getInventory().getItem(result.inventorySlot()));
        assertEquals(1, stored.getAmount());
        assertEquals(1, stored.getMaxStackSize());
        assertEquals(Component.text("Queued blade"), stored.getItemMeta().displayName());
        ItemIdentityReadResult.Tracked tracked = assertInstanceOf(
                ItemIdentityReadResult.Tracked.class,
                identityCodec.readIdentity(stored));
        assertEquals(delivery.identity(), tracked.identity());
    }

    @Test
    void fullInventoryReturnsNoSpaceWithoutDroppingOrReplacingAnything() {
        ItemStack filler = ItemStack.of(Material.COBBLESTONE, 64);
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int slot = 0; slot < contents.length; slot++) {
            player.getInventory().setItem(slot, filler.clone());
        }
        PreparedDirectDelivery delivery = prepared(
                templateCodec.encode(ItemStack.of(Material.DIAMOND)));

        PaperDirectDeliveryOperator.ApplyResult result = operator.apply(player, delivery);

        assertEquals(PaperDirectDeliveryOperator.ApplyResult.Status.NO_SPACE, result.status());
        for (ItemStack item : player.getInventory().getStorageContents()) {
            assertEquals(Material.COBBLESTONE, Objects.requireNonNull(item).getType());
            assertEquals(64, item.getAmount());
        }
    }

    @Test
    void corruptTemplateRequiresReviewWithoutMutatingInventory() {
        PreparedDirectDelivery delivery = prepared(
                new EncodedItemTemplate(1, new byte[] {1, 2, 3}));

        PaperDirectDeliveryOperator.ApplyResult result = operator.apply(player, delivery);

        assertEquals(
                PaperDirectDeliveryOperator.ApplyResult.Status.REVIEW_REQUIRED,
                result.status());
        assertTrue(player.getInventory().isEmpty());
    }

    private PreparedDirectDelivery prepared(EncodedItemTemplate template) {
        return new PreparedDirectDelivery(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                new LoreInstanceId(UUID.fromString(
                        "22222222-2222-2222-2222-222222222222")),
                new LoreDefinitionId(UUID.fromString(
                        "33333333-3333-3333-3333-333333333333")),
                player.getUniqueId(),
                new TemplateRevision(4),
                template,
                "direct-test",
                "44444444-4444-4444-4444-444444444444",
                31_000L,
                1,
                1_000L,
                1_000L);
    }
}
