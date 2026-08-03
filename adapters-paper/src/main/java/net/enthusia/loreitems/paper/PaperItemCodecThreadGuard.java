package net.enthusia.loreitems.paper;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.bukkit.Bukkit;

final class PaperItemCodecThreadGuard {
    private final BooleanSupplier primaryThread;

    PaperItemCodecThreadGuard(BooleanSupplier primaryThread) {
        this.primaryThread = Objects.requireNonNull(primaryThread, "primaryThread");
    }

    static PaperItemCodecThreadGuard system() {
        return new PaperItemCodecThreadGuard(Bukkit::isPrimaryThread);
    }

    void requirePrimaryThread() {
        if (!primaryThread.getAsBoolean()) {
            throw new IllegalStateException("Paper item codec access must run on the primary server thread");
        }
    }
}
