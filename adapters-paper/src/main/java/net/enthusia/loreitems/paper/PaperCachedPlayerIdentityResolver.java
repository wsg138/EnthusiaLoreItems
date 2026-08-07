package net.enthusia.loreitems.paper;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

public final class PaperCachedPlayerIdentityResolver {
    public Optional<UUID> resolve(String currentName) {
        Objects.requireNonNull(currentName, "currentName");
        String normalized = currentName.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("currentName must not be blank");
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(normalized);
        if (cached == null) {
            return Optional.empty();
        }
        String cachedName = cached.getName();
        if (cachedName == null || !cachedName.equalsIgnoreCase(normalized)) {
            return Optional.empty();
        }
        return Optional.of(cached.getUniqueId());
    }
}
