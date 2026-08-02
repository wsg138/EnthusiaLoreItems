package net.enthusia.loreitems.application;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class AtomicConfiguration {
    private final AtomicReference<FoundationConfiguration> current;

    public AtomicConfiguration(FoundationConfiguration initial) {
        current = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
    }

    public FoundationConfiguration current() {
        return current.get();
    }

    public ReloadResult replace(FoundationConfiguration candidate) {
        Objects.requireNonNull(candidate, "candidate");
        while (true) {
            FoundationConfiguration existing = current.get();
            if (!existing.hasSameStartupResources(candidate)) {
                return new ReloadResult(
                        false,
                        "Database timeout, queue capacity, and shutdown timeout require a restart.");
            }
            if (current.compareAndSet(existing, candidate)) {
                return new ReloadResult(true, "Configuration snapshot replaced atomically.");
            }
        }
    }

    public record ReloadResult(boolean applied, String detail) {
        public ReloadResult {
            Objects.requireNonNull(detail, "detail");
        }
    }
}
