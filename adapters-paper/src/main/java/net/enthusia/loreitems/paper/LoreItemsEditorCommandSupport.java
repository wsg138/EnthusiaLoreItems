package net.enthusia.loreitems.paper;

import java.util.Arrays;
import java.util.Objects;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Explicit private command input for the GUI editor (never public chat). */
final class LoreItemsEditorCommandSupport {
    private LoreItemsEditorCommandSupport() {}

    static boolean execute(
            CommandSender sender,
            String[] arguments,
            PaperTemplateEditorManager templateEditor) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(templateEditor, "templateEditor");
        if (!sender.hasPermission(PaperTemplateEditorManager.EDIT_PERMISSION)) {
            LoreItemsMessages.send(sender, "You do not have permission to edit lore-item templates.");
            return true;
        }
        if (!(sender instanceof Player player)) {
            LoreItemsMessages.send(sender, "The template editor requires an in-game player.");
            return true;
        }
        if (isCancel(arguments)) {
            templateEditor.cancelOwnDraft(player);
            return true;
        }
        int start = valueStart(arguments);
        if (start < 0 || arguments.length <= start) {
            LoreItemsMessages.send(sender,
                    "Usage: /loreitems set <value> | /loreitems editor set <value> | /loreitems editor cancel");
            return true;
        }
        templateEditor.submitOwnValue(player,
                String.join(" ", Arrays.copyOfRange(arguments, start, arguments.length)));
        return true;
    }

    private static int valueStart(String[] args) {
        if (args.length >= 1 && "set".equalsIgnoreCase(args[0])) {
            return 1;
        }
        if (args.length >= 2 && "editor".equalsIgnoreCase(args[0])
                && "set".equalsIgnoreCase(args[1])) {
            return 2;
        }
        return -1;
    }

    static boolean isCancel(String[] arguments) {
        return arguments.length == 2
                && "editor".equalsIgnoreCase(arguments[0])
                && "cancel".equalsIgnoreCase(arguments[1]);
    }
}
