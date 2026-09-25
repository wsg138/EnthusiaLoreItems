package net.enthusia.loreitems.paper;

import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/** Shared visual language for staff-facing inventory GUIs. */
final class PaperGuiStyle {
    enum Tone {
        PRIMARY(NamedTextColor.AQUA),
        INFO(NamedTextColor.WHITE),
        POSITIVE(NamedTextColor.GREEN),
        WARNING(NamedTextColor.GOLD),
        DESTRUCTIVE(NamedTextColor.RED),
        NAVIGATION(NamedTextColor.GRAY),
        METADATA(NamedTextColor.GOLD);

        private final NamedTextColor color;

        Tone(NamedTextColor color) {
            this.color = color;
        }
    }

    private PaperGuiStyle() {}

    static Component inventoryTitle(String text) {
        return text(text, NamedTextColor.GOLD);
    }

    static Component name(String text, Tone tone) {
        Objects.requireNonNull(tone, "tone");
        return text(text, tone.color);
    }

    static Component lore(String text) {
        if (text.isEmpty()) {
            return Component.empty().decoration(TextDecoration.ITALIC, false);
        }
        return text(text, NamedTextColor.GRAY);
    }

    private static Component text(String text, NamedTextColor color) {
        return Component.text(Objects.requireNonNull(text, "text"), color)
                .decoration(TextDecoration.ITALIC, false);
    }
}
