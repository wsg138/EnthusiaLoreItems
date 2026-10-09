package net.enthusia.loreitems.paper;

import java.util.Locale;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;

/** Common styling for staff-facing messages. */
final class LoreItemsMessages {
    private LoreItemsMessages() {}

    static void send(CommandSender sender, String message) {
        Objects.requireNonNull(sender, "sender").sendMessage(format(message));
    }

    static Component format(String message) {
        String value = Objects.requireNonNull(message, "message").toLowerCase(Locale.ROOT);
        NamedTextColor tone = NamedTextColor.GRAY;
        if (value.contains("failed") || value.contains("invalid")
                || value.contains("permission") || value.contains("unavailable")
                || value.contains("rejected") || value.contains("not found")) {
            tone = NamedTextColor.RED;
        } else if (value.contains("review") || value.contains("pending")
                || value.contains("already") || value.contains("cancelled")) {
            tone = NamedTextColor.YELLOW;
        } else if (value.contains("created") || value.contains("adopted")
                || value.contains("accepted") || value.contains("completed")) {
            tone = NamedTextColor.GREEN;
        }
        return Component.text("✦ ", TextColor.color(0x67E8F9))
                .append(Component.text("LORE ITEMS", TextColor.color(0xA78BFA),
                        TextDecoration.BOLD))
                .append(Component.text("  »  ", NamedTextColor.DARK_GRAY))
                .append(Component.text(message, tone).decoration(TextDecoration.ITALIC, false));
    }
}
