package net.enthusia.loreitems.paper;

import java.util.Locale;
import java.util.Objects;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public final class LoreItemsCommandExecutor implements CommandExecutor {
    private static final String CREATE_SUBCOMMAND = "create";
    private static final String ADOPT_SUBCOMMAND = "adopt";
    private static final String GIVE_SUBCOMMAND = "give";
    private static final String ANOMALIES_SUBCOMMAND = "anomalies";
    private static final String AUDIT_SUBCOMMAND = "audit";
    private static final String RECOVERY_SUBCOMMAND = "recovery";
    private static final String BROWSE_SUBCOMMAND = "browse";
    private static final String USAGE =
            "Usage: /loreitems create <lookup-key> <display name> | "
                    + "/loreitems adopt <lookup-key> | "
                    + "/loreitems give <lookup-key> [player] | "
                    + "/loreitems browse | "
                    + "/loreitems anomalies [page] | "
                    + "/loreitems audit <instance-uuid> [page] | "
                    + "/loreitems recovery [page]";

    private final CreateDefinitionCommandExecutor createExecutor;
    private final AdoptHeldItemCommandExecutor adoptExecutor;
    private final GiveLoreItemCommandExecutor giveExecutor;
    private final LoreItemsAdministrationCommandExecutor administrationExecutor;

    public LoreItemsCommandExecutor(
            CreateDefinitionCommandExecutor createExecutor,
            AdoptHeldItemCommandExecutor adoptExecutor,
            GiveLoreItemCommandExecutor giveExecutor) {
        this(createExecutor, adoptExecutor, giveExecutor, null);
    }

    public LoreItemsCommandExecutor(
            CreateDefinitionCommandExecutor createExecutor,
            AdoptHeldItemCommandExecutor adoptExecutor,
            GiveLoreItemCommandExecutor giveExecutor,
            LoreItemsAdministrationCommandExecutor administrationExecutor) {
        this.createExecutor = Objects.requireNonNull(createExecutor, "createExecutor");
        this.adoptExecutor = Objects.requireNonNull(adoptExecutor, "adoptExecutor");
        this.giveExecutor = Objects.requireNonNull(giveExecutor, "giveExecutor");
        this.administrationExecutor = administrationExecutor;
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
        String subcommand = arguments[0].toLowerCase(Locale.ROOT);
        return switch (subcommand) {
            case CREATE_SUBCOMMAND -> createExecutor.onCommand(sender, command, label, arguments);
            case ADOPT_SUBCOMMAND -> adoptExecutor.onCommand(sender, command, label, arguments);
            case GIVE_SUBCOMMAND -> giveExecutor.onCommand(sender, command, label, arguments);
            case BROWSE_SUBCOMMAND, ANOMALIES_SUBCOMMAND, AUDIT_SUBCOMMAND,
                    RECOVERY_SUBCOMMAND ->
                    executeAdministration(sender, command, label, arguments);
            default -> {
                sender.sendMessage(USAGE);
                yield true;
            }
        };
    }

    private boolean executeAdministration(
            CommandSender sender,
            Command command,
            String label,
            String[] arguments) {
        if (administrationExecutor == null) {
            sender.sendMessage(USAGE);
            return true;
        }
        return administrationExecutor.onCommand(sender, command, label, arguments);
    }
}
