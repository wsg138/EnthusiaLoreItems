package net.enthusia.loreitems.paper;

import java.util.Optional;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Reload-safe reference to one naturally encountered physical item. */
interface PaperTemplateUpdateReference {
    Optional<? extends Resolved> resolve(Plugin plugin);

    interface Resolved {
        ItemStack originalItem();

        boolean replace(ItemStack replacement);

        ItemStack readStored();

        boolean restore();
    }
}
