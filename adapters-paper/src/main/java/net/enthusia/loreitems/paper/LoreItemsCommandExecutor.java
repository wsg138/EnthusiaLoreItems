package net.enthusia.loreitems.paper;

import java.util.Objects;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public final class LoreItemsCommandExecutor implements CommandExecutor {
    private static final String CREATE_SUBCOMMAND = "create";
    private static final String ADOPT_SUBCOMMAND = "adopt";
    private static final String USAGE =
            "Usage: /loreitems create <lookup-key> <display name> | "
                    + "/loreitems adopt <lookup-key>";

    private final CreateDefinitionCommandExecutor createExecutor;
    private final AdoptHeldItemCommandExecutor adoptExecutor;

    public LoreItemsCommandExecutor(
            CreateDefinitionCommandExecutor createExecutor,
            AdoptHeldItemCommandExecutor adoptExecutor) {
        this.createExecutor = Objects.requireNonNull(createExecutor, "createExecutor");
        this.adoptExecutor = Objects.requireNonNull(adoptExecutor, "adoptExecutor");
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] arguments) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(arguments, "arguments");
        if (arguments.length == 0) {
            sender.sendMessage(USAGE);
            return true;
        }
        if (CREATE_SUBCOMMAND.equalsIgnoreCase(arguments[0])) {
            return createExecutor.onCommand(sender, command, label, arguments);
        }
        if (ADOPT_SUBCOMMAND.equalsIgnoreCase(arguments[0])) {
            return adoptExecutor.onCommand(sender, command, label, arguments);
        }
        sender.sendMessage(USAGE);
        return true;
    }
}
