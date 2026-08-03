package net.enthusia.loreitems.paper;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;
import net.enthusia.loreitems.application.AnomalyWarningSink;
import net.enthusia.loreitems.application.ItemAnomalyObservationUseCase;
import net.enthusia.loreitems.application.ItemIdentityReadResult;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.domain.LocationDescriptor;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

final class PaperItemAnomalyReporter implements AutoCloseable {
    private static final Duration REPORT_COOLDOWN = Duration.ofSeconds(5);
    private static final int COOLDOWN_CAPACITY_MULTIPLIER = 4;

    private final Plugin plugin;
    private final PaperItemIdentityCodec identityCodec = new PaperItemIdentityCodec();
    private final int maxInFlight;
    private final int maxCooldowns;
    private final Object lock = new Object();
    private final Set<ReportKey> inFlight = new HashSet<>();
    private final Map<ReportKey, Long> retryAfterNanos = new HashMap<>();

    private boolean closed;

    PaperItemAnomalyReporter(Plugin plugin, int maxInFlight) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        if (maxInFlight < 1) {
            throw new IllegalArgumentException("maxInFlight must be positive");
        }
        this.maxInFlight = maxInFlight;
        this.maxCooldowns = Math.multiplyExact(maxInFlight, COOLDOWN_CAPACITY_MULTIPLIER);
    }

    ItemIdentityReadResult inspect(
            ItemStack item,
            LocationDescriptor location,
            String source) {
        ItemIdentityReadResult result = identityCodec.readIdentity(item);
        if (result instanceof ItemIdentityReadResult.Invalid invalid
                && invalid.identityEvidence() != null) {
            record(
                    ItemAnomalyObservationUseCase.Kind.MALFORMED_STACK,
                    invalid.identityEvidence(),
                    location,
                    source,
                    invalid.failure().name() + ": " + invalid.detail());
        }
        return result;
    }

    void recordDuplicate(
            LoreItemIdentity identity,
            LocationDescriptor location,
            String source,
            String detail) {
        record(
                ItemAnomalyObservationUseCase.Kind.DUPLICATE_INSTANCE,
                identity,
                location,
                source,
                detail);
    }

    private void record(
            ItemAnomalyObservationUseCase.Kind kind,
            LoreItemIdentity identity,
            LocationDescriptor location,
            String source,
            String detail) {
        ItemAnomalyObservationUseCase useCase = plugin.getServer()
                .getServicesManager()
                .load(ItemAnomalyObservationUseCase.class);
        if (useCase == null) {
            return;
        }
        AnomalyWarningSink warningSink = plugin.getServer()
                .getServicesManager()
                .load(AnomalyWarningSink.class);
        ReportKey key = new ReportKey(
                kind,
                identity.instanceId().value(),
                location.type(),
                location.locationKey(),
                location.containerPath());
        if (!tryBegin(key)) {
            return;
        }
        ItemAnomalyObservationUseCase.Request request =
                new ItemAnomalyObservationUseCase.Request(
                        kind,
                        identity,
                        location,
                        source,
                        detail);
        try {
            useCase.record(request).whenComplete((result, failure) -> {
                finish(key);
                if (failure != null) {
                    plugin.getLogger().log(
                            Level.SEVERE,
                            "Could not persist lore-item identity anomaly evidence.",
                            unwrap(failure));
                    return;
                }
                if (result == null) {
                    plugin.getLogger().severe(
                            "Lore-item identity anomaly persistence returned no result.");
                    return;
                }
                if (result.shouldWarnStaff() && warningSink != null) {
                    warningSink.requestWarning();
                }
            });
        } catch (RuntimeException exception) {
            finish(key);
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Could not start lore-item identity anomaly persistence.",
                    exception);
        }
    }

    private boolean tryBegin(ReportKey key) {
        synchronized (lock) {
            if (closed) {
                return false;
            }
            long now = System.nanoTime();
            Long retryAt = retryAfterNanos.get(key);
            if (retryAt != null) {
                if (retryAt > now) {
                    return false;
                }
                retryAfterNanos.remove(key);
            }
            if (inFlight.contains(key) || inFlight.size() >= maxInFlight) {
                return false;
            }
            inFlight.add(key);
            return true;
        }
    }

    private void finish(ReportKey key) {
        synchronized (lock) {
            inFlight.remove(key);
            if (!closed && retryAfterNanos.size() < maxCooldowns) {
                retryAfterNanos.put(
                        key,
                        System.nanoTime() + REPORT_COOLDOWN.toNanos());
            }
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException exception && exception.getCause() != null) {
            return exception.getCause();
        }
        return throwable;
    }

    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            inFlight.clear();
            retryAfterNanos.clear();
        }
    }

    private record ReportKey(
            ItemAnomalyObservationUseCase.Kind kind,
            java.util.UUID instanceId,
            LocationDescriptor.Type locationType,
            String locationKey,
            String containerPath) {}
}
