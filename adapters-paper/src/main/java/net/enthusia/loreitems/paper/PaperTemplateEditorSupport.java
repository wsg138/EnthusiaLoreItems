package net.enthusia.loreitems.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

@SuppressWarnings({"PMD.AvoidInstantiatingObjectsInLoops", "PMD.AvoidLiteralsInIfCondition"})
final class PaperTemplateEditorSupport {
    private PaperTemplateEditorSupport() {}

    static NamespacedKey key(String value) {
        NamespacedKey key = NamespacedKey.fromString(Objects.requireNonNull(value, "value").strip());
        if (key == null) {
            throw new IllegalArgumentException("Expected a namespaced key such as minecraft:stone");
        }
        return key;
    }

    static boolean bool(String value) {
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "true", "yes", "visible", "on" -> true;
            case "false", "no", "hidden", "off" -> false;
            default -> throw new IllegalArgumentException("Expected true or false");
        };
    }

    static int integer(String value, int minimum, int maximum, String name) {
        final int parsed;
        try {
            parsed = Integer.parseInt(value.strip());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be a whole number", exception);
        }
        if (parsed < minimum || parsed > maximum) {
            throw new IllegalArgumentException(
                    name + " must be between " + minimum + " and " + maximum);
        }
        return parsed;
    }

    static double decimal(String value, String name) {
        final double parsed;
        try {
            parsed = Double.parseDouble(value.strip());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be a number", exception);
        }
        if (!Double.isFinite(parsed)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
        return parsed;
    }

    static Color color(String value) {
        String normalized = value.strip();
        if (normalized.startsWith("#")) {
            normalized = normalized.substring(1);
        }
        if (normalized.length() != 6) {
            throw new IllegalArgumentException("Color must be a six-digit hex value");
        }
        try {
            return Color.fromRGB(Integer.parseInt(normalized, 16));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Color must be a six-digit hex value", exception);
        }
    }

    static TextColor textColor(String value) {
        Color color = color(value);
        return TextColor.color(color.getRed(), color.getGreen(), color.getBlue());
    }

    static Component styledText(String input) {
        String[] parts = input.strip().split("\\s+", 3);
        if (parts.length < 2) {
            throw new IllegalArgumentException(
                    "Use literal <text>, solid <#RRGGBB> <text>, or gradient <colors> <text>");
        }
        return switch (parts[0].toLowerCase(Locale.ROOT)) {
            case "literal" -> plain(input.strip().substring(parts[0].length()).strip());
            case "solid" -> {
                if (parts.length < 3) {
                    throw new IllegalArgumentException("Use solid <#RRGGBB> <text>");
                }
                yield Component.text(parts[2], textColor(parts[1]))
                        .decoration(TextDecoration.ITALIC, false);
            }
            case "gradient" -> {
                if (parts.length < 3) {
                    throw new IllegalArgumentException(
                            "Use gradient <#RRGGBB,#RRGGBB,...> <text>");
                }
                yield gradient(parts[2], parseGradientColors(parts[1]));
            }
            default -> throw new IllegalArgumentException(
                    "Text style must be literal, solid, or gradient");
        };
    }

    static ItemStack normalized(ItemStack item) {
        ItemStack normalized = item.clone();
        normalized.setAmount(1);
        ItemMeta meta = requireMeta(normalized);
        meta.setMaxStackSize(1);
        applyMeta(normalized, meta);
        return normalized;
    }

    static ItemMeta requireMeta(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            throw new IllegalArgumentException("The selected material does not expose item metadata");
        }
        return meta;
    }

    static void applyMeta(ItemStack item, ItemMeta meta) {
        meta.setMaxStackSize(1);
        if (!item.setItemMeta(meta)) {
            throw new IllegalArgumentException("Paper rejected the edited item metadata");
        }
        item.setAmount(1);
    }

    static String remainder(String input, String prefix) {
        String stripped = input.strip();
        if (!stripped.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Expected " + prefix);
        }
        String remainder = stripped.substring(prefix.length()).strip();
        if (remainder.isEmpty()) {
            throw new IllegalArgumentException("A value is required after " + prefix);
        }
        return remainder;
    }

    private static Component plain(String text) {
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Text must not be empty");
        }
        return Component.text(text).decoration(TextDecoration.ITALIC, false);
    }

    private static List<TextColor> parseGradientColors(String value) {
        String[] tokens = value.split(",");
        if (tokens.length < 2 || tokens.length > 16) {
            throw new IllegalArgumentException("A gradient requires 2-16 colors");
        }
        List<TextColor> colors = new ArrayList<>(tokens.length);
        for (String token : tokens) {
            colors.add(textColor(token));
        }
        return List.copyOf(colors);
    }

    private static Component gradient(String text, List<TextColor> colors) {
        if (text.isEmpty()) {
            throw new IllegalArgumentException("Text must not be empty");
        }
        int[] codePoints = text.codePoints().toArray();
        Component result = Component.empty();
        for (int index = 0; index < codePoints.length; index++) {
            double position = codePoints.length == 1 ? 0D : (double) index / (codePoints.length - 1);
            double scaled = position * (colors.size() - 1);
            int left = Math.min((int) Math.floor(scaled), colors.size() - 1);
            int right = Math.min(left + 1, colors.size() - 1);
            double fraction = scaled - left;
            TextColor color = interpolate(colors.get(left), colors.get(right), fraction);
            result = result.append(Component.text(new String(Character.toChars(codePoints[index])), color)
                    .decoration(TextDecoration.ITALIC, false));
        }
        return result;
    }

    private static TextColor interpolate(TextColor left, TextColor right, double fraction) {
        int red = channel(left.red(), right.red(), fraction);
        int green = channel(left.green(), right.green(), fraction);
        int blue = channel(left.blue(), right.blue(), fraction);
        return TextColor.color(red, green, blue);
    }

    private static int channel(int left, int right, double fraction) {
        return (int) Math.round(left + ((right - left) * fraction));
    }
}
