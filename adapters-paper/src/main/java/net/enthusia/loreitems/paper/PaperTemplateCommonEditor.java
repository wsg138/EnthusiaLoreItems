package net.enthusia.loreitems.paper;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;

@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.AvoidLiteralsInIfCondition", "PMD.CyclomaticComplexity", "PMD.NullAssignment"})
final class PaperTemplateCommonEditor {
    private static final Map<String, CommonAction> ACTIONS = Map.ofEntries(
            Map.entry("custom-name", PaperTemplateCommonEditor::customName),
            Map.entry("item-name", PaperTemplateCommonEditor::itemName),
            Map.entry("lore", PaperTemplateCommonEditor::lore),
            Map.entry("enchant", PaperTemplateCommonEditor::enchant),
            Map.entry("glint", PaperTemplateCommonEditor::glint),
            Map.entry("durability", PaperTemplateCommonEditor::durability),
            Map.entry("attribute", PaperTemplateCommonEditor::attribute),
            Map.entry("item-model", PaperTemplateCommonEditor::itemModel),
            Map.entry("max-stack", PaperTemplateCommonEditor::maxStack),
            Map.entry("custom-model-data", PaperTemplateCommonEditor::customModelData),
            Map.entry("flags", PaperTemplateCommonEditor::flags),
            Map.entry("tooltip", PaperTemplateCommonEditor::tooltip));

    PaperTemplateEditResult apply(ItemStack source, String action, String input) {
        ItemStack item = source.clone();
        try {
            if (action.equals("material")) {
                item = material(item, input);
                return accepted(item, "Base material changed to " + item.getType().getKey());
            }
            CommonAction editor = ACTIONS.get(action);
            if (editor == null) {
                return PaperTemplateEditResult.rejected(source, "Unsupported editor action");
            }
            return accepted(item, editor.apply(item, input));
        } catch (IllegalArgumentException exception) {
            return PaperTemplateEditResult.rejected(source, exception.getMessage());
        }
    }

    private static PaperTemplateEditResult accepted(ItemStack item, String detail) {
        return PaperTemplateEditResult.accepted(
                PaperTemplateEditorSupport.normalized(item), detail);
    }

    private static ItemStack material(ItemStack item, String input) {
        Material material = Material.matchMaterial(input.strip());
        if (material == null || material.isAir() || !material.isItem()) {
            throw new IllegalArgumentException("Material must be a valid non-air item");
        }
        return item.withType(material);
    }

    private static String customName(ItemStack item, String input) {
        ItemMeta meta = PaperTemplateEditorSupport.requireMeta(item);
        meta.customName(clearableText(input));
        PaperTemplateEditorSupport.applyMeta(item, meta);
        return "Custom name updated";
    }

    private static String itemName(ItemStack item, String input) {
        ItemMeta meta = PaperTemplateEditorSupport.requireMeta(item);
        meta.itemName(clearableText(input));
        PaperTemplateEditorSupport.applyMeta(item, meta);
        return "Item name updated";
    }

    private static Component clearableText(String input) {
        String normalized = input.strip();
        return normalized.equalsIgnoreCase("clear")
                ? null
                : PaperTemplateEditorSupport.styledText(normalized);
    }

    private static String lore(ItemStack item, String input) {
        ItemMeta meta = PaperTemplateEditorSupport.requireMeta(item);
        List<Component> lines = new ArrayList<>();
        List<Component> existing = meta.lore();
        if (existing != null) {
            lines.addAll(existing);
        }
        String[] operation = input.strip().split("\\s+", 2);
        switch (operation[0].toLowerCase(Locale.ROOT)) {
            case "clear" -> lines.clear();
            case "add" -> lines.add(PaperTemplateEditorSupport.styledText(requiredTail(operation)));
            case "edit" -> editLore(lines, requiredTail(operation));
            case "remove" -> lines.remove(index(requiredTail(operation), lines.size(), "lore line"));
            case "move" -> moveLore(lines, requiredTail(operation));
            default -> throw new IllegalArgumentException(
                    "Use add, edit, remove, move, or clear for lore");
        }
        meta.lore(lines.isEmpty() ? null : lines);
        PaperTemplateEditorSupport.applyMeta(item, meta);
        return "Lore updated; " + lines.size() + " line(s) remain";
    }

    private static void editLore(List<Component> lines, String tail) {
        String[] values = tail.split("\\s+", 2);
        if (values.length < 2) {
            throw new IllegalArgumentException("Use edit <line> <text style>");
        }
        lines.set(index(values[0], lines.size(), "lore line"),
                PaperTemplateEditorSupport.styledText(values[1]));
    }

    private static void moveLore(List<Component> lines, String tail) {
        String[] values = tail.split("\\s+");
        if (values.length != 2) {
            throw new IllegalArgumentException("Use move <from line> <to line>");
        }
        int from = index(values[0], lines.size(), "source lore line");
        int to = index(values[1], lines.size(), "target lore line");
        Component moved = lines.remove(from);
        lines.add(to, moved);
    }

    private static String enchant(ItemStack item, String input) {
        ItemMeta meta = PaperTemplateEditorSupport.requireMeta(item);
        String[] values = input.strip().split("\\s+");
        editEnchantments(meta, values);
        PaperTemplateEditorSupport.applyMeta(item, meta);
        return "Enchantments updated";
    }

    private static void editEnchantments(ItemMeta meta, String[] values) {
        String usage =
                "Use set <key> <level>, remove <key>, clear, or tooltip visible|hidden";
        switch (values[0].toLowerCase(Locale.ROOT)) {
            case "clear" -> {
                requireLength(values, 1, usage);
                meta.removeEnchantments();
            }
            case "remove" -> {
                requireLength(values, 2, usage);
                meta.removeEnchant(enchantment(values[1]));
            }
            case "tooltip" -> {
                requireLength(values, 2, usage);
                setFlag(meta, ItemFlag.HIDE_ENCHANTS,
                        !PaperTemplateEditorSupport.bool(values[1]));
            }
            case "set" -> {
                requireLength(values, 3, usage);
                int level = PaperTemplateEditorSupport.integer(
                        values[2], 1, 32_767, "level");
                meta.addEnchant(enchantment(values[1]), level, true);
            }
            default -> throw new IllegalArgumentException(usage);
        }
    }

    private static Enchantment enchantment(String value) {
        Enchantment enchantment = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT)
                .get(PaperTemplateEditorSupport.key(value));
        if (enchantment == null) {
            throw new IllegalArgumentException("Unknown enchantment key");
        }
        return enchantment;
    }

    private static String glint(ItemStack item, String input) {
        ItemMeta meta = PaperTemplateEditorSupport.requireMeta(item);
        String value = input.strip().toLowerCase(Locale.ROOT);
        meta.setEnchantmentGlintOverride(switch (value) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            case "unset", "clear" -> null;
            default -> throw new IllegalArgumentException("Use true, false, or unset");
        });
        PaperTemplateEditorSupport.applyMeta(item, meta);
        return "Glint override updated";
    }

    private static String durability(ItemStack item, String input) {
        String[] values = input.strip().split("\\s+");
        ItemMeta meta = PaperTemplateEditorSupport.requireMeta(item);
        if (values.length == 2 && values[0].equalsIgnoreCase("unbreakable")) {
            meta.setUnbreakable(PaperTemplateEditorSupport.bool(values[1]));
        } else if (values.length == 2 && values[0].equalsIgnoreCase("damage")) {
            if (!(meta instanceof Damageable damageable)) {
                throw new IllegalArgumentException("This material cannot store durability damage");
            }
            int maximum = Math.max(0, item.getType().getMaxDurability());
            damageable.setDamage(PaperTemplateEditorSupport.integer(
                    values[1], 0, maximum, "damage"));
        } else {
            throw new IllegalArgumentException("Use damage <value> or unbreakable true|false");
        }
        PaperTemplateEditorSupport.applyMeta(item, meta);
        return "Durability settings updated";
    }

    private static String attribute(ItemStack item, String input) {
        ItemMeta meta = PaperTemplateEditorSupport.requireMeta(item);
        String[] values = input.strip().split("\\s+");
        editAttributes(meta, values);
        PaperTemplateEditorSupport.applyMeta(item, meta);
        return "Attribute modifiers updated";
    }

    private static void editAttributes(ItemMeta meta, String[] values) {
        String usage =
                "Use set <attribute> <modifier-key> <operation> <amount> <slot/group>, "
                        + "remove <modifier-key>, or clear";
        switch (values[0].toLowerCase(Locale.ROOT)) {
            case "clear" -> {
                requireLength(values, 1, usage);
                meta.setAttributeModifiers(null);
            }
            case "remove" -> {
                requireLength(values, 2, usage);
                removeModifier(meta, PaperTemplateEditorSupport.key(values[1]));
            }
            case "set" -> {
                requireLength(values, 6, usage);
                setAttribute(meta, values);
            }
            default -> throw new IllegalArgumentException(usage);
        }
    }

    private static void setAttribute(ItemMeta meta, String[] values) {
        Attribute attribute = Registry.ATTRIBUTE.get(
                PaperTemplateEditorSupport.key(values[1]));
        if (attribute == null) {
            throw new IllegalArgumentException("Unknown attribute key");
        }
        NamespacedKey modifierKey = PaperTemplateEditorSupport.key(values[2]);
        AttributeModifier.Operation operation = parseOperation(values[3]);
        double amount = PaperTemplateEditorSupport.decimal(values[4], "amount");
        EquipmentSlotGroup group = EquipmentSlotGroup.getByName(values[5]);
        if (group == null) {
            throw new IllegalArgumentException("Unknown equipment slot/group");
        }
        removeModifier(meta, modifierKey);
        if (!meta.addAttributeModifier(
                attribute,
                new AttributeModifier(modifierKey, amount, operation, group))) {
            throw new IllegalArgumentException("Paper rejected the attribute modifier");
        }
    }

    private static void requireLength(String[] values, int expected, String usage) {
        if (values.length != expected) {
            throw new IllegalArgumentException(usage);
        }
    }

    private static AttributeModifier.Operation parseOperation(String value) {
        try {
            return AttributeModifier.Operation.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Operation must be ADD_NUMBER, ADD_SCALAR, or MULTIPLY_SCALAR_1", exception);
        }
    }

    private static void removeModifier(ItemMeta meta, NamespacedKey key) {
        if (!meta.hasAttributeModifiers()) {
            return;
        }
        List<AttributeEntry> matches = new ArrayList<>();
        for (Attribute attribute : Registry.ATTRIBUTE) {
            Collection<AttributeModifier> modifiers = meta.getAttributeModifiers(attribute);
            if (modifiers == null) {
                continue;
            }
            for (AttributeModifier modifier : modifiers) {
                if (key.equals(modifier.getKey())) {
                    matches.add(new AttributeEntry(attribute, modifier));
                }
            }
        }
        matches.forEach(entry -> meta.removeAttributeModifier(entry.attribute(), entry.modifier()));
    }

    private static String itemModel(ItemStack item, String input) {
        ItemMeta meta = PaperTemplateEditorSupport.requireMeta(item);
        meta.setItemModel(input.strip().equalsIgnoreCase("clear")
                ? null
                : PaperTemplateEditorSupport.key(input));
        PaperTemplateEditorSupport.applyMeta(item, meta);
        return "Item model updated";
    }

    private static String maxStack(ItemStack item, String input) {
        int requested = PaperTemplateEditorSupport.integer(input, 1, 99, "maximum stack size");
        if (requested != 1) {
            throw new IllegalArgumentException(
                    "Tracked templates are unstackable; maximum stack size must be 1");
        }
        ItemMeta meta = PaperTemplateEditorSupport.requireMeta(item);
        meta.setMaxStackSize(1);
        PaperTemplateEditorSupport.applyMeta(item, meta);
        return "Maximum stack size validated and normalized to 1";
    }

    private static String customModelData(ItemStack item, String input) {
        ItemMeta meta = PaperTemplateEditorSupport.requireMeta(item);
        String[] values = input.strip().split("\\s+", 2);
        if (values[0].equalsIgnoreCase("clear")) {
            meta.setCustomModelDataComponent(null);
        } else {
            if (values.length < 2) {
                throw new IllegalArgumentException(
                        "Use floats, flags, strings, colors, or clear");
            }
            CustomModelDataComponent component = meta.getCustomModelDataComponent();
            switch (values[0].toLowerCase(Locale.ROOT)) {
                case "floats" -> component.setFloats(parseFloats(values[1]));
                case "flags" -> component.setFlags(parseBooleans(values[1]));
                case "strings" -> component.setStrings(parseStrings(values[1]));
                case "colors" -> component.setColors(parseColors(values[1]));
                default -> throw new IllegalArgumentException(
                        "Use floats, flags, strings, colors, or clear");
            }
            meta.setCustomModelDataComponent(component);
        }
        PaperTemplateEditorSupport.applyMeta(item, meta);
        return "Custom model data updated";
    }

    private static List<Float> parseFloats(String value) {
        List<Float> result = new ArrayList<>();
        for (String token : value.split(",")) {
            try {
                float parsed = Float.parseFloat(token.strip());
                if (!Float.isFinite(parsed)) {
                    throw new NumberFormatException("non-finite");
                }
                result.add(parsed);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Custom model floats must be finite", exception);
            }
        }
        return List.copyOf(result);
    }

    private static List<Boolean> parseBooleans(String value) {
        List<Boolean> result = new ArrayList<>();
        for (String token : value.split(",")) {
            result.add(PaperTemplateEditorSupport.bool(token));
        }
        return List.copyOf(result);
    }

    private static List<String> parseStrings(String value) {
        List<String> result = new ArrayList<>();
        for (String token : value.split("\\|", -1)) {
            String normalized = token.strip();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("Custom model strings must not be empty");
            }
            result.add(normalized);
        }
        return List.copyOf(result);
    }

    private static List<Color> parseColors(String value) {
        List<Color> result = new ArrayList<>();
        for (String token : value.split(",")) {
            result.add(PaperTemplateEditorSupport.color(token));
        }
        return List.copyOf(result);
    }

    private static String flags(ItemStack item, String input) {
        ItemMeta meta = PaperTemplateEditorSupport.requireMeta(item);
        String[] values = input.strip().split("\\s+");
        if (values.length == 1 && values[0].equalsIgnoreCase("clear")) {
            meta.removeItemFlags(ItemFlag.values());
        } else if (values.length == 2) {
            ItemFlag flag = itemFlag(values[1]);
            if (values[0].equalsIgnoreCase("add")) {
                meta.addItemFlags(flag);
            } else if (values[0].equalsIgnoreCase("remove")) {
                meta.removeItemFlags(flag);
            } else {
                throw new IllegalArgumentException("Use add <flag>, remove <flag>, or clear");
            }
        } else {
            throw new IllegalArgumentException("Use add <flag>, remove <flag>, or clear");
        }
        PaperTemplateEditorSupport.applyMeta(item, meta);
        return "Item flags updated";
    }

    private static ItemFlag itemFlag(String value) {
        try {
            return ItemFlag.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown item flag", exception);
        }
    }

    private static String tooltip(ItemStack item, String input) {
        ItemMeta meta = PaperTemplateEditorSupport.requireMeta(item);
        String[] values = input.strip().split("\\s+", 2);
        if (values.length != 2) {
            throw new IllegalArgumentException(
                    "Use hide true|false or style <namespaced-key|clear>");
        }
        if (values[0].equalsIgnoreCase("hide")) {
            meta.setHideTooltip(PaperTemplateEditorSupport.bool(values[1]));
        } else if (values[0].equalsIgnoreCase("style")) {
            meta.setTooltipStyle(values[1].equalsIgnoreCase("clear")
                    ? null
                    : PaperTemplateEditorSupport.key(values[1]));
        } else {
            throw new IllegalArgumentException(
                    "Use hide true|false or style <namespaced-key|clear>");
        }
        PaperTemplateEditorSupport.applyMeta(item, meta);
        return "Tooltip controls updated";
    }

    private static void setFlag(ItemMeta meta, ItemFlag flag, boolean hidden) {
        if (hidden) {
            meta.addItemFlags(flag);
        } else {
            meta.removeItemFlags(flag);
        }
    }

    private static int index(String value, int size, String name) {
        if (size == 0) {
            throw new IllegalArgumentException("There are no " + name + " entries");
        }
        return PaperTemplateEditorSupport.integer(value, 1, size, name) - 1;
    }

    private static String requiredTail(String[] operation) {
        if (operation.length < 2 || operation[1].isBlank()) {
            throw new IllegalArgumentException("This operation requires a value");
        }
        return operation[1];
    }

    private record AttributeEntry(Attribute attribute, AttributeModifier modifier) {
        private AttributeEntry {
            Objects.requireNonNull(attribute, "attribute");
            Objects.requireNonNull(modifier, "modifier");
        }
    }

    @FunctionalInterface
    private interface CommonAction {
        String apply(ItemStack item, String input);
    }

}
