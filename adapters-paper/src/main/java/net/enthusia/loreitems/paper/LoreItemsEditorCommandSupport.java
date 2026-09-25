package net.enthusia.loreitems.paper;

import java.util.Objects;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

final class LoreItemsEditorCommandSupport {
    private static final int CANCEL_ARGUMENT_COUNT = 2;

    private LoreItemsEditorCommandSupport() {}

    static boolean execute(
            CommandSender sender,
            String[] arguments,
            PaperTemplateEditorManager templateEditor) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(templateEditor, "templateEditor");
        if (!sender.hasPermission(PaperTemplateEditorManager.EDIT_PERMISSION)) {
            sender.sendMessage("You do not have permission to edit lore-item templates.");
            return true;
        }
        if (!isCancel(arguments)) {
            sender.sendMessage("Usage: /loreitems editor cancel");
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("The template editor requires an in-game player.");
            return true;
        }
        templateEditor.cancelOwnDraft(player);
        return true;
    }

    static boolean isCancel(String[] arguments) {
        return arguments.length == CANCEL_ARGUMENT_COUNT
                && "cancel".equalsIgnoreCase(arguments[1]);
    }
}
