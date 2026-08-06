package net.enthusia.loreitems.paper;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.DyeColor;
import org.bukkit.FireworkEffect;
import org.bukkit.Registry;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.inventory.meta.FireworkEffectMeta;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;

@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.AvoidLiteralsInIfCondition", "PMD.CyclomaticComplexity"})
final class PaperTemplateSpecializedEditor {
    PaperTemplateEditResult apply(ItemStack source, String action, String input) {
        ItemStack item = source.clone();
        try {
            String detail = switch (action) {
                case "dye" -> dye(item, input);
                case "potion" -> potion(item, input);
                case "trim" -> trim(item, input);
                case "banner" -> banner(item, input);
                case "profile" -> profile(item, input);
                case "firework" -> firework(item, input);
                default -> throw new IllegalArgumentException("Unsupported editor action");
            };
            return PaperTemplateEditResult.accepted(
                    PaperTemplateEditorSupport.normalized(item), detail);
        } catch (IllegalArgumentException exception) {
            return PaperTemplateEditResult.rejected(source, exception.getMessage());
        }
    }

    private static String dye(ItemStack item, String input) {
        ItemMeta raw = PaperTemplateEditorSupport.requireMeta(item);
        if (!(raw instanceof LeatherArmorMeta meta)) {
            throw new IllegalArgumentException("This material is not dyeable through the Paper API");
        }
        if (input.strip().equalsIgnoreCase("clear")) {
            meta.setColor(Bukkit.getItemFactory().getDefaultLeatherColor());
        } else {
            meta.setColor(PaperTemplateEditorSupport.color(input));
        }
        PaperTemplateEditorSupport.applyMeta(item, meta);
        return "Dyed color updated";
    }

    private static String potion(ItemStack item, String input) {
        ItemMeta raw = PaperTemplateEditorSupport.requireMeta(item);
        if (!(raw instanceof PotionMeta meta)) {
            throw new IllegalArgumentException("This material does not support potion data");
        }
        String[] values = input.strip().split("\\s+");
        switch (values[0].toLowerCase(Locale.ROOT)) {
            case "base" -> setPotionBase(meta, values);
            case "clear-effects" -> {
                requireLength(values, 1);
                meta.clearCustomEffects();
            }
            case "remove-effect" -> {
                requireLength(values, 2);
                meta.removeCustomEffect(effectType(values[1]));
            }
            case "color" -> {
                requireLength(values, 2);
                meta.setColor(PaperTemplateEditorSupport.color(values[1]));
            }
            case "clear-color" -> {
                requireLength(values, 1);
                meta.setColor(null);
            }
            case "set-effect" -> setPotionEffect(meta, values);
            default -> throw potionUsage();
        }
        PaperTemplateEditorSupport.applyMeta(item, meta);
        return "Potion components updated";
    }

    private static void setPotionBase(PotionMeta meta, String[] values) {
        requireLength(values, 2);
        PotionType type = Registry.POTION.get(PaperTemplateEditorSupport.key(values[1]));
        if (type == null) {
            throw new IllegalArgumentException("Unknown potion base type");
        }
        meta.setBasePotionType(type);
    }

    private static void setPotionEffect(PotionMeta meta, String[] values) {
        requireLength(values, 7);
        PotionEffect effect = new PotionEffect(
                effectType(values[1]),
                PaperTemplateEditorSupport.integer(values[2], 1, Integer.MAX_VALUE, "duration"),
                PaperTemplateEditorSupport.integer(values[3], 0, 255, "amplifier"),
                PaperTemplateEditorSupport.bool(values[4]),
                PaperTemplateEditorSupport.bool(values[5]),
                PaperTemplateEditorSupport.bool(values[6]));
        meta.addCustomEffect(effect, true);
    }

    private static void requireLength(String[] values, int expected) {
        if (values.length != expected) {
            throw potionUsage();
        }
    }

    private static void requireLength(String[] values, int expected, String usage) {
        if (values.length != expected) {
            throw new IllegalArgumentException(usage);
        }
    }

    private static IllegalArgumentException potionUsage() {
        return new IllegalArgumentException(
                "Use base <key>, set-effect <key> <ticks> <amplifier> <ambient> "
                        + "<particles> <icon>, remove-effect <key>, clear-effects, "
                        + "color <hex>, or clear-color");
    }

    private static PotionEffectType effectType(String value) {
        PotionEffectType type = Registry.MOB_EFFECT.get(PaperTemplateEditorSupport.key(value));
        if (type == null) {
            throw new IllegalArgumentException("Unknown potion effect key");
        }
        return type;
    }

    private static String trim(ItemStack item, String input) {
        ItemMeta raw = PaperTemplateEditorSupport.requireMeta(item);
        if (!(raw instanceof ArmorMeta meta)) {
            throw new IllegalArgumentException("This material does not support armor trim");
        }
        String[] values = input.strip().split("\\s+");
        if (values.length == 1 && values[0].equalsIgnoreCase("clear")) {
            meta.setTrim(null);
        } else if (values.length == 2) {
            TrimMaterial material = RegistryAccess.registryAccess().getRegistry(RegistryKey.TRIM_MATERIAL).get(
                    PaperTemplateEditorSupport.key(values[0]));
            TrimPattern pattern = RegistryAccess.registryAccess().getRegistry(RegistryKey.TRIM_PATTERN).get(
                    PaperTemplateEditorSupport.key(values[1]));
            if (material == null || pattern == null) {
                throw new IllegalArgumentException("Unknown trim material or pattern key");
            }
            meta.setTrim(new ArmorTrim(material, pattern));
        } else {
            throw new IllegalArgumentException("Use <material-key> <pattern-key> or clear");
        }
        PaperTemplateEditorSupport.applyMeta(item, meta);
        return "Armor trim updated";
    }

    private static String banner(ItemStack item, String input) {
        ItemMeta raw = PaperTemplateEditorSupport.requireMeta(item);
        if (!(raw instanceof BannerMeta meta)) {
            throw new IllegalArgumentException("This material does not support banner patterns");
        }
        String[] values = input.strip().split("\\s+");
        editBanner(meta, values);
        PaperTemplateEditorSupport.applyMeta(item, meta);
        return "Banner patterns updated";
    }

    private static void editBanner(BannerMeta meta, String[] values) {
        String usage =
                "Use add <dye> <pattern-key>, set <index> <dye> <pattern-key>, "
                        + "remove <index>, or clear";
        switch (values[0].toLowerCase(Locale.ROOT)) {
            case "clear" -> {
                requireLength(values, 1, usage);
                meta.setPatterns(List.of());
            }
            case "remove" -> {
                requireLength(values, 2, usage);
                meta.removePattern(index(
                        values[1], meta.numberOfPatterns(), "banner pattern"));
            }
            case "add" -> {
                requireLength(values, 3, usage);
                meta.addPattern(pattern(values[1], values[2]));
            }
            case "set" -> {
                requireLength(values, 4, usage);
                meta.setPattern(index(
                                values[1], meta.numberOfPatterns(), "banner pattern"),
                        pattern(values[2], values[3]));
            }
            default -> throw new IllegalArgumentException(usage);
        }
    }

    private static Pattern pattern(String dyeValue, String typeValue) {
        final DyeColor dye;
        try {
            dye = DyeColor.valueOf(dyeValue.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown dye color", exception);
        }
        PatternType type = RegistryAccess.registryAccess().getRegistry(RegistryKey.BANNER_PATTERN)
                .get(PaperTemplateEditorSupport.key(typeValue));
        if (type == null) {
            throw new IllegalArgumentException("Unknown banner pattern key");
        }
        return new Pattern(dye, type);
    }

    private static String profile(ItemStack item, String input) {
        ItemMeta raw = PaperTemplateEditorSupport.requireMeta(item);
        if (!(raw instanceof SkullMeta meta)) {
            throw new IllegalArgumentException("This material does not support a player profile");
        }
        String normalized = input.strip();
        if (normalized.equalsIgnoreCase("clear")) {
            meta.setPlayerProfile(null);
        } else {
            String[] values = normalized.split("\\s+", 2);
            final UUID uuid;
            try {
                uuid = UUID.fromString(values[0]);
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Profile UUID is invalid", exception);
            }
            String name = values.length == 2 ? values[1].strip() : null;
            if (name != null && (name.isEmpty() || name.length() > 16)) {
                throw new IllegalArgumentException("Profile name must be 1-16 characters");
            }
            meta.setPlayerProfile(Bukkit.getServer().createProfileExact(uuid, name));
        }
        PaperTemplateEditorSupport.applyMeta(item, meta);
        return "Player profile updated";
    }

    private static String firework(ItemStack item, String input) {
        ItemMeta raw = PaperTemplateEditorSupport.requireMeta(item);
        if (raw instanceof FireworkMeta rocket) {
            editRocket(rocket, input);
            PaperTemplateEditorSupport.applyMeta(item, rocket);
        } else if (raw instanceof FireworkEffectMeta star) {
            editStar(star, input);
            PaperTemplateEditorSupport.applyMeta(item, star);
        } else {
            throw new IllegalArgumentException("This material does not support firework effects");
        }
        return "Firework components updated";
    }

    private static void editRocket(FireworkMeta meta, String input) {
        String[] values = input.strip().split("\\s+");
        String usage =
                "Use power <0-255>, add <type> <colors> <fade|none> <flicker> <trail>, "
                        + "remove <index>, or clear";
        switch (values[0].toLowerCase(Locale.ROOT)) {
            case "power" -> {
                requireLength(values, 2, usage);
                meta.setPower(PaperTemplateEditorSupport.integer(
                        values[1], 0, 255, "power"));
            }
            case "clear" -> {
                requireLength(values, 1, usage);
                meta.clearEffects();
            }
            case "remove" -> {
                requireLength(values, 2, usage);
                meta.removeEffect(index(
                        values[1], meta.getEffectsSize(), "firework effect"));
            }
            case "add" -> {
                requireLength(values, 6, usage);
                meta.addEffect(effect(values, 1));
            }
            default -> throw new IllegalArgumentException(usage);
        }
    }

    private static void editStar(FireworkEffectMeta meta, String input) {
        String[] values = input.strip().split("\\s+");
        if (values.length == 1 && values[0].equalsIgnoreCase("clear")) {
            meta.setEffect(null);
        } else if (values.length == 6 && values[0].equalsIgnoreCase("set")) {
            meta.setEffect(effect(values, 1));
        } else {
            throw new IllegalArgumentException(
                    "Use set <type> <colors> <fade|none> <flicker> <trail> or clear");
        }
    }

    private static FireworkEffect effect(String[] values, int offset) {
        final FireworkEffect.Type type;
        try {
            type = FireworkEffect.Type.valueOf(values[offset].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Unknown firework effect type", exception);
        }
        List<Color> colors = colors(values[offset + 1]);
        List<Color> fade = values[offset + 2].equalsIgnoreCase("none")
                ? List.of()
                : colors(values[offset + 2]);
        FireworkEffect.Builder builder = FireworkEffect.builder()
                .with(type)
                .withColor(colors)
                .flicker(PaperTemplateEditorSupport.bool(values[offset + 3]))
                .trail(PaperTemplateEditorSupport.bool(values[offset + 4]));
        if (!fade.isEmpty()) {
            builder.withFade(fade);
        }
        return builder.build();
    }

    private static List<Color> colors(String value) {
        List<Color> colors = new ArrayList<>();
        for (String token : value.split(",")) {
            colors.add(PaperTemplateEditorSupport.color(token));
        }
        if (colors.isEmpty() || colors.size() > 16) {
            throw new IllegalArgumentException("A firework effect requires 1-16 colors");
        }
        return List.copyOf(colors);
    }

    private static int index(String value, int size, String name) {
        if (size == 0) {
            throw new IllegalArgumentException("There are no " + name + " entries");
        }
        return PaperTemplateEditorSupport.integer(value, 1, size, name) - 1;
    }
}
