package net.enthusia.loreitems.paper;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Semaphore;
import net.enthusia.loreitems.application.TemplateManagementSnapshot;
import net.enthusia.loreitems.application.TemplateManagementUseCase;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Bounded asynchronous loading for the read-only template-management screen. */
final class PaperTemplateManagementLoader {
    private static final int MAX_QUERIES = 32;
    private static final String UNAVAILABLE =
            "Template editing is unavailable while durable storage is read-only or initializing.";

    private final Plugin plugin;
    private final PaperTemplateEditorRenderer renderer;
    private final PaperItemTemplateCodec templateCodec;
    private final FailureHandler failureHandler;
    private final MainThreadExecutor mainThreadExecutor;
    private final Semaphore queryCapacity = new Semaphore(MAX_QUERIES);

    PaperTemplateManagementLoader(
            Plugin plugin,
            PaperTemplateEditorRenderer renderer,
            PaperItemTemplateCodec templateCodec,
            FailureHandler failureHandler,
            MainThreadExecutor mainThreadExecutor) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.templateCodec = Objects.requireNonNull(templateCodec, "templateCodec");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
        this.mainThreadExecutor = Objects.requireNonNull(mainThreadExecutor, "mainThreadExecutor");
    }

    void open(UUID playerId, LoreDefinitionId definitionId, int returnPage) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.hasPermission(
                LoreItemsAdministrationCommandExecutor.AUDIT_PERMISSION)) {
            return;
        }
        TemplateManagementUseCase useCase = resolveUseCase();
        if (useCase == null) {
            player.sendMessage(UNAVAILABLE);
            return;
        }
        if (!queryCapacity.tryAcquire()) {
            player.sendMessage("Too many template-management queries are active.");
            return;
        }
        loadSnapshot(playerId, definitionId, returnPage, useCase);
    }

    private void loadSnapshot(
            UUID playerId,
            LoreDefinitionId definitionId,
            int returnPage,
            TemplateManagementUseCase useCase) {
        CompletionStage<Optional<TemplateManagementSnapshot>> stage;
        try {
            stage = Objects.requireNonNull(
                    useCase.findSnapshot(definitionId), "template snapshot stage");
        } catch (RuntimeException exception) {
            queryCapacity.release();
            failureHandler.handle(playerId, "load template management", exception);
            return;
        }
        stage.whenComplete((snapshot, failure) -> {
            queryCapacity.release();
            if (failure != null) {
                failureHandler.handle(playerId, "load template management", failure);
                return;
            }
            mainThreadExecutor.execute(() -> show(playerId, snapshot, returnPage));
        });
    }

    private void show(
            UUID playerId,
            Optional<TemplateManagementSnapshot> snapshot,
            int returnPage) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) {
            return;
        }
        if (snapshot == null || snapshot.isEmpty()) {
            player.sendMessage("That lore definition is no longer active.");
            return;
        }
        try {
            TemplateManagementSnapshot value = snapshot.orElseThrow();
            renderer.showManagement(
                    player,
                    value,
                    templateCodec.decode(value.currentTemplate()),
                    returnPage);
        } catch (RuntimeException exception) {
            failureHandler.handle(playerId, "decode template preview", exception);
        }
    }

    private TemplateManagementUseCase resolveUseCase() {
        return plugin.getServer().getServicesManager().load(TemplateManagementUseCase.class);
    }

    @FunctionalInterface
    interface FailureHandler {
        void handle(UUID playerId, String operation, Throwable throwable);
    }

    @FunctionalInterface
    interface MainThreadExecutor {
        void execute(Runnable action);
    }
}
