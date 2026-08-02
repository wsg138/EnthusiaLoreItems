package net.enthusia.loreitems.plugin;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.api.v1.LoreDeliveryResult;
import net.enthusia.loreitems.api.v1.LoreDeliveryStatus;
import net.enthusia.loreitems.api.v1.LoreItemsServiceV1;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class LoreItemsPlugin extends JavaPlugin {
    private final LoreItemsServiceV1 unavailableService = new UnavailableService();

    @Override
    public void onEnable() {
        getServer()
                .getServicesManager()
                .register(
                        LoreItemsServiceV1.class,
                        unavailableService,
                        this,
                        ServicePriority.Normal);
        getLogger().info(
                "Foundation bootstrap enabled; durable delivery remains unavailable until initialized.");
    }

    @Override
    public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
    }

    private static final class UnavailableService implements LoreItemsServiceV1 {
        @Override
        public CompletionStage<LoreDeliveryResult> queueDelivery(
                String definitionKey,
                UUID playerId,
                String externalOperationId) {
            return CompletableFuture.completedFuture(
                    new LoreDeliveryResult(
                            LoreDeliveryStatus.SERVICE_UNAVAILABLE,
                            externalOperationId,
                            "The durable delivery runtime is not active in the foundation bootstrap."));
        }
    }
}
