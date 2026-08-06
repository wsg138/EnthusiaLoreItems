package net.enthusia.loreitems.paper;

import java.util.Objects;
import java.util.Optional;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Reload-safe physical reference used by both template updates and destructive removal. */
interface PaperTemplateUpdateReference {
    Optional<? extends Resolved> resolve(Plugin plugin);

    DestructiveLocation destructiveLocation();

    interface Resolved {
        ItemStack originalItem();

        boolean replace(ItemStack replacement);

        boolean remove();

        ItemStack readStored();

        boolean restore();
    }

    record DestructiveLocation(String locationType, String locationKey, String containerPath) {
        public DestructiveLocation {
            Objects.requireNonNull(locationType, "locationType");
            Objects.requireNonNull(locationKey, "locationKey");
            if (locationType.isBlank() || locationKey.isBlank()) {
                throw new IllegalArgumentException(
                        "Destructive location evidence must not be blank");
            }
            if (containerPath != null && containerPath.isBlank()) {
                containerPath = null;
            }
        }
    }
}
