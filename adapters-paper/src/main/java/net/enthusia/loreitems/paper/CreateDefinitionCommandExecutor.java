package net.enthusia.loreitems.paper;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;
import net.enthusia.loreitems.application.CreateDefinitionRequest;
import net.enthusia.loreitems.application.CreateDefinitionResult;
import net.enthusia.loreitems.application.CreateDefinitionStatus;
import net.enthusia.loreitems.application.CreateDefinitionUseCase;
import net.enthusia.loreitems.application.EncodedItemTemplate;
import net.enthusia.loreitems.application.ItemCodecException;
import net.enthusia.loreitems.domain.DefinitionKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;

public final class CreateDefinitionCommandExecutor implements CommandExecutor {
    public static final String CREATE_PERMISSION = "enthusia.loreitems.admin.create";

    private static final String CREATE_SUBCOMMAND = "create";
    private static final int MINIMUM_CREATE_ARGUMENTS = 3;
    private static final int DISPLAY_NAME_START_INDEX = 2;
    private static final String USAGE =
            "Usage: /loreitems create <lookup-key> <display name>";

    private final Plugin plugin;
    private final CreateDefinitionUseCase useCase;
    private final PaperHeldItemDefinitionSnapshotter snapshotter;

    public CreateDefinitionCommandExecutor(
            Plugin plugin,
            CreateDefinitionUseCase useCase,
            PaperHeldItemDefinitionSnapshotter snapshotter) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.useCase = Objects.requireNonNull(useCase, "useCase");
        this.snapshotter = Objects.requireNonNull(snapshotter, "snapshotter");
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
        if (arguments.length == 0
                || !CREATE_SUBCOMMAND.equalsIgnoreCase(arguments[0])) {
            sender.sendMessage(USAGE);
            return true;
        }
        executeCreate(sender, arguments);
        return true;
    }

    private void executeCreate(CommandSender sender, String[] arguments) {
        if (!sender.hasPermission(CREATE_PERMISSION)) {
            sender.sendMessage("You do not have permission to create lore definitions.");
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command requires a player holding the source item.");
            return;
        }
        if (arguments.length < MINIMUM_CREATE_ARGUMENTS) {
            sender.sendMessage(USAGE);
            return;
        }
        submitCreate(player, arguments);
    }

    private void submitCreate(Player player, String[] arguments) {
        try {
            DefinitionKey key = new DefinitionKey(arguments[1]);
            EncodedItemTemplate template =
                    snapshotter.snapshot(player.getInventory().getItemInMainHand());
            String displayName = String.join(
                    " ", Arrays.copyOfRange(
                            arguments, DISPLAY_NAME_START_INDEX, arguments.length));
            CreateDefinitionRequest request = new CreateDefinitionRequest(
                    key, displayName, template, player.getUniqueId());
            UUID playerId = player.getUniqueId();
            useCase.create(request).whenComplete((result, throwable) ->
                    scheduleResult(playerId, key, result, throwable));
        } catch (IllegalArgumentException | ItemCodecException exception) {
            player.sendMessage(exception.getMessage());
        }
    }

    private void scheduleResult(
            UUID playerId,
            DefinitionKey key,
            CreateDefinitionResult result,
            Throwable throwable) {
        try {
            plugin.getServer().getScheduler().runTask(
                    plugin,
                    () -> notifyPlayer(playerId, key, result, throwable));
        } catch (IllegalPluginAccessException exception) {
            plugin.getLogger().log(
                    Level.WARNING,
                    "Could not schedule the definition-creation result during shutdown.",
                    exception);
        }
    }

    private void notifyPlayer(
            UUID playerId,
            DefinitionKey key,
            CreateDefinitionResult result,
            Throwable throwable) {
        Player player = plugin.getServer().getPlayer(playerId);
        if (throwable != null) {
            logFailure(key, throwable);
            if (player != null) {
                player.sendMessage("Lore definition creation failed; check the server log.");
            }
            return;
        }
        if (player != null) {
            sendResult(player, key, Objects.requireNonNull(result, "result"));
        }
    }

    private void logFailure(DefinitionKey key, Throwable throwable) {
        plugin.getLogger().log(
                Level.SEVERE,
                "Definition creation failed for key " + key.value(),
                unwrap(throwable));
    }

    private static void sendResult(
            Player player, DefinitionKey key, CreateDefinitionResult result) {
        CreateDefinitionStatus status = result.status();
        switch (status) {
            case CREATED -> player.sendMessage(
                    "Created lore definition '" + key.value() + "' from the held item.");
            case ACTIVE_KEY_EXISTS -> player.sendMessage(
                    "An active lore definition already uses key '" + key.value() + "'.");
            case SERVICE_UNAVAILABLE -> player.sendMessage(
                    "Lore item storage is not currently available for writes.");
            default -> throw new IllegalStateException(
                    "Unsupported create-definition status: " + status);
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException exception && exception.getCause() != null) {
            return exception.getCause();
        }
        return throwable;
    }
}
