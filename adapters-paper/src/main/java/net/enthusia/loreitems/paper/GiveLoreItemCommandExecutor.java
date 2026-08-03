package net.enthusia.loreitems.paper;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.logging.Level;
import net.enthusia.loreitems.api.v1.LoreDeliveryResult;
import net.enthusia.loreitems.api.v1.LoreDeliveryStatus;
import net.enthusia.loreitems.api.v1.LoreItemsServiceV1;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;

public final class GiveLoreItemCommandExecutor implements CommandExecutor {
    public static final String GIVE_PERMISSION = "enthusia.loreitems.admin.give";

    private static final String GIVE_SUBCOMMAND = "give";
    private static final String USAGE =
            "Usage: /loreitems give <lookup-key> [online/cached player name or UUID]";
    private static final int MINIMUM_ARGUMENT_COUNT = 2;
    private static final int MAXIMUM_ARGUMENT_COUNT = 3;

    private final Plugin plugin;
    private final LoreItemsServiceV1 service;
    private final Consumer<UUID> deliveryWakeup;

    public GiveLoreItemCommandExecutor(
            Plugin plugin,
            LoreItemsServiceV1 service,
            Consumer<UUID> deliveryWakeup) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.service = Objects.requireNonNull(service, "service");
        this.deliveryWakeup = Objects.requireNonNull(deliveryWakeup, "deliveryWakeup");
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
        if (arguments.length == 0 || !GIVE_SUBCOMMAND.equalsIgnoreCase(arguments[0])) {
            sender.sendMessage(USAGE);
            return true;
        }
        executeGive(sender, arguments);
        return true;
    }

    private void executeGive(CommandSender sender, String[] arguments) {
        if (!sender.hasPermission(GIVE_PERMISSION)) {
            sender.sendMessage("You do not have permission to give lore items.");
            return;
        }
        if (arguments.length < MINIMUM_ARGUMENT_COUNT
                || arguments.length > MAXIMUM_ARGUMENT_COUNT) {
            sender.sendMessage(USAGE);
            return;
        }
        UUID targetPlayerId = resolveTarget(sender, arguments);
        if (targetPlayerId == null) {
            return;
        }
        CommandActor actor = CommandActor.capture(sender);
        String operationId = "admin-give:" + actor.auditId() + ':' + UUID.randomUUID();
        service.queueDelivery(arguments[1], targetPlayerId, operationId)
                .whenComplete((result, throwable) -> handleResult(
                        actor, targetPlayerId, result, throwable));
    }

    private UUID resolveTarget(CommandSender sender, String[] arguments) {
        if (arguments.length == MINIMUM_ARGUMENT_COUNT) {
            if (sender instanceof Player player) {
                return player.getUniqueId();
            }
            sender.sendMessage("Console must specify a cached player name or UUID.");
            return null;
        }
        String value = arguments[2];
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            Player online = plugin.getServer().getPlayerExact(value);
            if (online != null) {
                return online.getUniqueId();
            }
            OfflinePlayer cached = plugin.getServer().getOfflinePlayerIfCached(value);
            if (cached != null) {
                return cached.getUniqueId();
            }
            sender.sendMessage(
                    "That player is not online or cached. Use the player's UUID to queue offline delivery.");
            return null;
        }
    }

    private void handleResult(
            CommandActor actor,
            UUID targetPlayerId,
            LoreDeliveryResult result,
            Throwable throwable) {
        if (throwable != null) {
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Direct-give command failed before returning a durable result.",
                    unwrap(throwable));
            notifyActor(actor, "The lore-item delivery could not be queued.");
            return;
        }
        if (result.status() == LoreDeliveryStatus.ACCEPTED_QUEUED
                || result.status() == LoreDeliveryStatus.ALREADY_ACCEPTED) {
            deliveryWakeup.accept(targetPlayerId);
        }
        notifyActor(actor, commandMessage(result));
    }

    private static String commandMessage(LoreDeliveryResult result) {
        return switch (result.status()) {
            case ACCEPTED_QUEUED ->
                    "Lore item queued. It will deliver when the player is online with inventory space.";
            case ALREADY_ACCEPTED -> "That durable delivery operation was already accepted.";
            case UNKNOWN_DEFINITION -> "No active lore definition has that lookup key.";
            case SERVICE_UNAVAILABLE -> "Lore-item storage is unavailable; nothing was queued.";
            case VALIDATION_FAILURE -> "The lore-item delivery request was invalid.";
        };
    }

    private void notifyActor(CommandActor actor, String message) {
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (actor.playerId() == null) {
                    plugin.getServer().getConsoleSender().sendMessage(message);
                    return;
                }
                Player player = plugin.getServer().getPlayer(actor.playerId());
                if (player != null) {
                    player.sendMessage(message);
                }
            });
        } catch (IllegalPluginAccessException exception) {
            plugin.getLogger().log(
                    Level.FINE,
                    "Could not schedule direct-give command notification during shutdown.",
                    exception);
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException exception && exception.getCause() != null) {
            return exception.getCause();
        }
        return throwable;
    }

    private record CommandActor(UUID playerId, String auditId) {
        private static CommandActor capture(CommandSender sender) {
            if (sender instanceof Player player) {
                return new CommandActor(player.getUniqueId(), player.getUniqueId().toString());
            }
            return new CommandActor(null, "console");
        }
    }
}
