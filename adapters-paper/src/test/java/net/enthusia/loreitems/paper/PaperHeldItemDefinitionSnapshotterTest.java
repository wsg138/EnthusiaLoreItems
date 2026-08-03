package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Objects;
import java.util.UUID;
import net.enthusia.loreitems.application.EncodedItemTemplate;
import net.enthusia.loreitems.application.ItemCodecException;
import net.enthusia.loreitems.application.ItemIdentityReadResult;
import net.enthusia.loreitems.application.LoreItemIdentity;
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

class PaperHeldItemDefinitionSnapshotterTest {
    private static final NamespacedKey INSTANCE_KEY = key("instance_id");
    private static final NamespacedKey FOREIGN_KEY = key("snapshot_foreign");

    private PaperItemIdentityCodec identityCodec;
    private PaperItemTemplateCodec templateCodec;
    private PaperHeldItemDefinitionSnapshotter snapshotter;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        identityCodec = new PaperItemIdentityCodec();
        templateCodec = new PaperItemTemplateCodec();
        snapshotter = new PaperHeldItemDefinitionSnapshotter(identityCodec, templateCodec);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void snapshotsArbitraryComponentsWithoutMutatingHeldItemOrRetainingIdentity() {
        ItemStack held = ItemStack.of(Material.COMPASS, 4);
        ItemMeta meta = Objects.requireNonNull(held.getItemMeta());
        meta.displayName(Component.text("Original appearance"));
        meta.getPersistentDataContainer().set(
                FOREIGN_KEY, PersistentDataType.STRING, "preserved");
        assertTrue(held.setItemMeta(meta));
        ItemStack tracked = identityCodec.writeIdentity(held, identity());

        EncodedItemTemplate encoded = snapshotter.snapshot(tracked);
        ItemStack decoded = templateCodec.decode(encoded);

        assertEquals(1, tracked.getAmount());
        assertInstanceOf(ItemIdentityReadResult.Tracked.class, identityCodec.readIdentity(tracked));
        assertInstanceOf(ItemIdentityReadResult.Untracked.class, identityCodec.readIdentity(decoded));
        assertEquals(1, decoded.getAmount());
        assertEquals(1, decoded.getMaxStackSize());
        ItemMeta decodedMeta = Objects.requireNonNull(decoded.getItemMeta());
        assertEquals(Component.text("Original appearance"), decodedMeta.displayName());
        assertEquals(
                "preserved",
                decodedMeta.getPersistentDataContainer().get(
                        FOREIGN_KEY, PersistentDataType.STRING));
    }

    @Test
    void rejectsMalformedTrackedEvidenceWithoutChangingIt() {
        ItemStack malformed = identityCodec.writeIdentity(
                ItemStack.of(Material.DIAMOND), identity());
        ItemMeta meta = Objects.requireNonNull(malformed.getItemMeta());
        meta.getPersistentDataContainer().remove(INSTANCE_KEY);
        assertTrue(malformed.setItemMeta(meta));

        assertThrows(ItemCodecException.class, () -> snapshotter.snapshot(malformed));
        assertInstanceOf(ItemIdentityReadResult.Invalid.class, identityCodec.readIdentity(malformed));
    }

    private static LoreItemIdentity identity() {
        return new LoreItemIdentity(
                new LoreDefinitionId(UUID.fromString("33333333-3333-3333-3333-333333333333")),
                new LoreInstanceId(UUID.fromString("44444444-4444-4444-4444-444444444444")),
                new TemplateRevision(2));
    }

    private static NamespacedKey key(String value) {
        return Objects.requireNonNull(NamespacedKey.fromString("enthusialoreitems:" + value));
    }
}
