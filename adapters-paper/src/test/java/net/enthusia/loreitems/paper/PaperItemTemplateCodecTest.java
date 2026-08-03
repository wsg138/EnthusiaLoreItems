package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.enthusia.loreitems.application.EncodedItemTemplate;
import net.enthusia.loreitems.application.ItemCodecException;
import net.enthusia.loreitems.application.ItemCodecFailure;
import net.enthusia.loreitems.application.ItemIdentityReadResult;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class PaperItemTemplateCodecTest {
    private static final NamespacedKey FOREIGN_KEY = key("foreign_template_value");

    private PaperItemIdentityCodec identityCodec;
    private PaperItemTemplateCodec templateCodec;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        identityCodec = new PaperItemIdentityCodec();
        templateCodec = new PaperItemTemplateCodec();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void roundTripPreservesArbitraryComponentsAndStripsInstanceIdentity() {
        ItemStack base = ItemStack.of(Material.DIAMOND_SWORD);
        ItemMeta meta = Objects.requireNonNull(base.getItemMeta());
        meta.displayName(Component.text("Foundation Blade"));
        meta.lore(List.of(Component.text("Uncommon components must survive")));
        meta.setUnbreakable(true);
        Damageable damageable = assertInstanceOf(Damageable.class, meta);
        damageable.setDamage(13);
        meta.getPersistentDataContainer().set(FOREIGN_KEY, PersistentDataType.STRING, "preserved");
        assertTrue(base.setItemMeta(meta));
        ItemStack tracked = identityCodec.writeIdentity(base, identity());

        EncodedItemTemplate encoded = templateCodec.encode(tracked);
        ItemStack decoded = templateCodec.decode(encoded);

        assertEquals(PaperItemTemplateCodec.CURRENT_VERSION, encoded.codecVersion());
        assertInstanceOf(ItemIdentityReadResult.Tracked.class, identityCodec.readIdentity(tracked));
        assertInstanceOf(ItemIdentityReadResult.Untracked.class, identityCodec.readIdentity(decoded));
        assertEquals(1, decoded.getAmount());
        assertEquals(1, decoded.getMaxStackSize());
        ItemMeta decodedMeta = Objects.requireNonNull(decoded.getItemMeta());
        assertEquals(Component.text("Foundation Blade"), decodedMeta.displayName());
        assertEquals(List.of(Component.text("Uncommon components must survive")), decodedMeta.lore());
        assertTrue(decodedMeta.isUnbreakable());
        assertEquals(13, assertInstanceOf(Damageable.class, decodedMeta).getDamage());
        assertEquals(
                "preserved",
                decodedMeta.getPersistentDataContainer().get(FOREIGN_KEY, PersistentDataType.STRING));
    }

    @Test
    void normalizesAmountAndMaximumStackWithoutMutatingSource() {
        ItemStack source = ItemStack.of(Material.PAPER, 8);

        EncodedItemTemplate encoded = templateCodec.encode(source);
        ItemStack decoded = templateCodec.decode(encoded);

        assertEquals(8, source.getAmount());
        assertEquals(64, source.getMaxStackSize());
        assertEquals(1, decoded.getAmount());
        assertEquals(1, decoded.getMaxStackSize());
    }

    @Test
    void rejectsNewerCodecVersionsAndCorruptPayloads() {
        EncodedItemTemplate valid = templateCodec.encode(ItemStack.of(Material.COMPASS));
        ItemCodecException unsupported = assertThrows(
                ItemCodecException.class,
                () -> templateCodec.decode(
                        new EncodedItemTemplate(valid.codecVersion() + 1, valid.payload())));
        assertEquals(ItemCodecFailure.UNSUPPORTED_VERSION, unsupported.failure());

        ItemCodecException corrupt = assertThrows(
                ItemCodecException.class,
                () -> templateCodec.decode(new EncodedItemTemplate(1, new byte[] {1, 2, 3})));
        assertEquals(ItemCodecFailure.CORRUPT_PAYLOAD, corrupt.failure());
    }

    @Test
    void rejectsPaperAccessWhenNotOnPrimaryThread() {
        PaperItemTemplateCodec guarded =
                new PaperItemTemplateCodec(new PaperItemCodecThreadGuard(() -> false));

        assertThrows(IllegalStateException.class, () -> guarded.encode(ItemStack.of(Material.PAPER)));
        assertThrows(
                IllegalStateException.class,
                () -> guarded.decode(new EncodedItemTemplate(1, new byte[] {1})));
    }

    private static LoreItemIdentity identity() {
        return new LoreItemIdentity(
                new LoreDefinitionId(UUID.fromString("33333333-3333-3333-3333-333333333333")),
                new LoreInstanceId(UUID.fromString("44444444-4444-4444-4444-444444444444")),
                new TemplateRevision(11));
    }

    private static NamespacedKey key(String value) {
        return Objects.requireNonNull(NamespacedKey.fromString("enthusialoreitems:" + value));
    }
}
