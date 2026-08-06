package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.FireworkEffectMeta;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class PaperTemplateDraftEditorTest {
    private static final PlainTextComponentSerializer PLAIN =
            PlainTextComponentSerializer.plainText();
    private static final NamespacedKey FOREIGN_KEY = Objects.requireNonNull(
            NamespacedKey.fromString("test:editor-foreign"));

    private PaperTemplateDraftEditor editor;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        editor = new PaperTemplateDraftEditor();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void editsNamesAndLoreStylesWithoutChangingSourceOrForeignData() {
        ItemStack source = ItemStack.of(Material.PAPER, 8);
        ItemMeta sourceMeta = Objects.requireNonNull(source.getItemMeta());
        sourceMeta.getPersistentDataContainer().set(
                FOREIGN_KEY, PersistentDataType.STRING, "preserved");
        assertTrue(source.setItemMeta(sourceMeta));

        ItemStack custom = apply(source, "custom-name", "solid #112233 Solid Name");
        assertEquals("Solid Name", plain(custom.getItemMeta().customName()));
        assertEquals(TextColor.fromHexString("#112233"), custom.getItemMeta().customName().color());
        ItemStack itemName = apply(custom, "item-name", "literal Fixed Name");
        assertEquals("Fixed Name", plain(itemName.getItemMeta().itemName()));
        ItemStack firstLore = apply(itemName, "lore", "add literal First line");
        ItemStack secondLore = apply(
                firstLore,
                "lore",
                "add gradient #ff0000,#0000ff Gradient line");
        ItemStack editedLore = apply(
                secondLore,
                "lore",
                "edit 1 solid #00ff00 Edited first");
        ItemStack reordered = apply(editedLore, "lore", "move 2 1");
        List<Component> lore = Objects.requireNonNull(reordered.getItemMeta().lore());

        assertEquals(List.of("Gradient line", "Edited first"), lore.stream().map(this::plain).toList());
        assertEquals("preserved", reordered.getItemMeta().getPersistentDataContainer().get(
                FOREIGN_KEY, PersistentDataType.STRING));
        assertEquals(1, reordered.getAmount());
        assertEquals(1, reordered.getItemMeta().getMaxStackSize());
        assertFalse(source.getItemMeta().hasCustomName());
        assertEquals(8, source.getAmount());

        ItemStack removed = apply(reordered, "lore", "remove 1");
        assertEquals(List.of("Edited first"), Objects.requireNonNull(removed.getItemMeta().lore())
                .stream().map(this::plain).toList());
        assertNull(apply(removed, "lore", "clear").getItemMeta().lore());
        assertFalse(apply(itemName, "custom-name", "clear").getItemMeta().hasCustomName());
        assertFalse(apply(itemName, "item-name", "clear").getItemMeta().hasItemName());
    }

    @Test
    void validatesMaterialStackSizeDurabilityAndUnbreakableState() {
        ItemStack paper = ItemStack.of(Material.PAPER, 2);
        ItemStack sword = apply(paper, "material", "minecraft:diamond_sword");
        assertEquals(Material.DIAMOND_SWORD, sword.getType());
        ItemStack damaged = apply(sword, "durability", "damage 15");
        assertEquals(15, assertInstanceOf(Damageable.class, damaged.getItemMeta()).getDamage());
        ItemStack unbreakable = apply(damaged, "durability", "unbreakable true");
        assertTrue(unbreakable.getItemMeta().isUnbreakable());
        ItemStack stack = apply(unbreakable, "max-stack", "1");
        assertEquals(1, stack.getItemMeta().getMaxStackSize());

        assertRejected(sword, "durability", "damage 999999");
        assertRejected(sword, "max-stack", "2");
        assertRejected(sword, "material", "minecraft:air");
    }

    @Test
    void editsEnchantmentsTooltipGlintFlagsAndTooltipStyle() {
        ItemStack sword = ItemStack.of(Material.DIAMOND_SWORD);
        ItemStack enchanted = apply(sword, "enchant", "set minecraft:sharpness 7");
        Enchantment sharpness = Objects.requireNonNull(
                RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT)
                        .get(NamespacedKey.minecraft("sharpness")));
        assertEquals(7, enchanted.getItemMeta().getEnchantLevel(sharpness));
        ItemStack hidden = apply(enchanted, "enchant", "tooltip hidden");
        assertTrue(hidden.getItemMeta().hasItemFlag(ItemFlag.HIDE_ENCHANTS));
        ItemStack visible = apply(hidden, "enchant", "tooltip visible");
        assertFalse(visible.getItemMeta().hasItemFlag(ItemFlag.HIDE_ENCHANTS));
        ItemMeta glintTrue = apply(visible, "glint", "true").getItemMeta();
        assertTrue(glintTrue.hasEnchantmentGlintOverride());
        assertEquals(Boolean.TRUE, glintTrue.getEnchantmentGlintOverride());
        ItemMeta glintFalse = apply(visible, "glint", "false").getItemMeta();
        assertTrue(glintFalse.hasEnchantmentGlintOverride());
        assertEquals(Boolean.FALSE, glintFalse.getEnchantmentGlintOverride());
        assertFalse(apply(visible, "glint", "unset")
                .getItemMeta().hasEnchantmentGlintOverride());
        ItemStack flagged = apply(visible, "flags", "add hide_attributes");
        assertTrue(flagged.getItemMeta().hasItemFlag(ItemFlag.HIDE_ATTRIBUTES));
        assertFalse(apply(flagged, "flags", "remove hide_attributes")
                .getItemMeta().hasItemFlag(ItemFlag.HIDE_ATTRIBUTES));
        assertTrue(apply(visible, "tooltip", "hide true").getItemMeta().isHideTooltip());
        ItemStack styledTooltip = apply(
                visible, "tooltip", "style minecraft:test_style");
        assertEquals(1, styledTooltip.getItemMeta().getMaxStackSize());
        apply(styledTooltip, "tooltip", "style clear");
        assertEquals(0, apply(enchanted, "enchant", "clear").getItemMeta().getEnchants().size());
    }

    @Test
    void editsAttributesModelsAndModernCustomModelData() {
        ItemStack sword = ItemStack.of(Material.DIAMOND_SWORD);
        ItemStack attributed = apply(
                sword,
                "attribute",
                "set minecraft:attack_damage enthusia:test_damage add_number 4.5 mainhand");
        Attribute attackDamage = Objects.requireNonNull(
                org.bukkit.Registry.ATTRIBUTE.get(NamespacedKey.minecraft("attack_damage")));
        assertEquals(1, Objects.requireNonNull(
                attributed.getItemMeta().getAttributeModifiers(attackDamage)).size());
        ItemStack replaced = apply(
                attributed,
                "attribute",
                "set minecraft:attack_damage enthusia:test_damage add_number 9 mainhand");
        assertEquals(1, Objects.requireNonNull(
                replaced.getItemMeta().getAttributeModifiers(attackDamage)).size());
        ItemStack removed = apply(replaced, "attribute", "remove enthusia:test_damage");
        assertTrue(removed.getItemMeta().getAttributeModifiers(attackDamage) == null
                || removed.getItemMeta().getAttributeModifiers(attackDamage).isEmpty());

        ItemStack modeled = apply(sword, "item-model", "enthusia:blade");
        assertTrue(modeled.getItemMeta().hasMaxStackSize());
        apply(modeled, "item-model", "clear");

        ItemStack floats = apply(sword, "custom-model-data", "floats 1.5,2.25");
        ItemStack flags = apply(floats, "custom-model-data", "flags true,false,true");
        ItemStack strings = apply(flags, "custom-model-data", "strings first|second value");
        ItemStack colors = apply(strings, "custom-model-data", "colors #010203,#abcdef");
        apply(colors, "custom-model-data", "clear");
        assertRejected(sword, "custom-model-data", "floats NaN");
        assertRejected(sword, "custom-model-data", "strings first||third");
    }

    @Test
    void editsDyePotionTrimBannerProfileAndFireworks() {
        ItemStack leather = apply(
                ItemStack.of(Material.LEATHER_CHESTPLATE), "dye", "#123456");
        assertEquals(Color.fromRGB(0x123456),
                assertInstanceOf(LeatherArmorMeta.class, leather.getItemMeta()).getColor());

        ItemStack potion = apply(ItemStack.of(Material.POTION), "potion", "base minecraft:strong_healing");
        PotionMeta potionMeta = assertInstanceOf(PotionMeta.class, potion.getItemMeta());
        assertNotNull(potionMeta.getBasePotionType());
        potion = apply(
                potion,
                "potion",
                "set-effect minecraft:speed 200 2 false true true");
        assertEquals(1, assertInstanceOf(PotionMeta.class, potion.getItemMeta()).getCustomEffects().size());
        potion = apply(potion, "potion", "color #112233");
        assertEquals(Color.fromRGB(0x112233),
                assertInstanceOf(PotionMeta.class, potion.getItemMeta()).getColor());
        assertNull(assertInstanceOf(PotionMeta.class,
                apply(potion, "potion", "clear-color").getItemMeta()).getColor());

        ItemStack armor = apply(
                ItemStack.of(Material.NETHERITE_CHESTPLATE),
                "trim",
                "minecraft:gold minecraft:sentry");
        assertNotNull(assertInstanceOf(ArmorMeta.class, armor.getItemMeta()).getTrim());
        assertNull(assertInstanceOf(ArmorMeta.class,
                apply(armor, "trim", "clear").getItemMeta()).getTrim());

        ItemStack banner = apply(
                ItemStack.of(Material.WHITE_BANNER),
                "banner",
                "add red minecraft:stripe_center");
        assertEquals(1, assertInstanceOf(BannerMeta.class, banner.getItemMeta()).numberOfPatterns());
        assertEquals(0, assertInstanceOf(BannerMeta.class,
                apply(banner, "banner", "clear").getItemMeta()).numberOfPatterns());

        UUID profileId = UUID.randomUUID();
        ItemStack head = apply(
                ItemStack.of(Material.PLAYER_HEAD),
                "profile",
                profileId + " Example");
        assertEquals(profileId,
                assertInstanceOf(SkullMeta.class, head.getItemMeta()).getPlayerProfile().getId());
        assertNull(assertInstanceOf(SkullMeta.class,
                apply(head, "profile", "clear").getItemMeta()).getPlayerProfile());

        ItemStack rocket = apply(ItemStack.of(Material.FIREWORK_ROCKET), "firework", "power 3");
        rocket = apply(
                rocket,
                "firework",
                "add ball #ff0000,#00ff00 #0000ff true false");
        FireworkMeta rocketMeta = assertInstanceOf(FireworkMeta.class, rocket.getItemMeta());
        assertEquals(3, rocketMeta.getPower());
        assertEquals(1, rocketMeta.getEffectsSize());
        ItemStack star = apply(
                ItemStack.of(Material.FIREWORK_STAR),
                "firework",
                "set burst #123456 none false true");
        assertNotNull(assertInstanceOf(FireworkEffectMeta.class, star.getItemMeta()).getEffect());
        assertNull(assertInstanceOf(FireworkEffectMeta.class,
                apply(star, "firework", "clear").getItemMeta()).getEffect());
    }

    private ItemStack apply(ItemStack source, String action, String input) {
        PaperTemplateEditResult result = editor.apply(source, action, input);
        assertTrue(result.accepted(), result.detail());
        return result.item();
    }

    private void assertRejected(ItemStack source, String action, String input) {
        PaperTemplateEditResult result = editor.apply(source, action, input);
        assertFalse(result.accepted());
        assertEquals(source, result.item());
    }

    private String plain(Component component) {
        return PLAIN.serialize(Objects.requireNonNull(component));
    }
}
