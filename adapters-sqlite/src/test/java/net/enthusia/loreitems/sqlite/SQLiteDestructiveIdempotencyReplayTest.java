package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.StartRequest;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.StartStatus;
import net.enthusia.loreitems.domain.DestructiveOperationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SQLiteDestructiveIdempotencyReplayTest {
    private static final Instant NOW = Instant.ofEpochMilli(2_000L);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String IDEMPOTENCY_KEY = "shared-destructive-key";

    @TempDir
    Path temporaryDirectory;

    @Test
    void reusedKeyCannotAliasDifferentDestructiveConfirmation() {
        try (SQLiteDestructiveTestFixture fixture =
                new SQLiteDestructiveTestFixture(temporaryDirectory, "replay.db", CLOCK)) {
            var seed = fixture.seed(true);
            var administration = fixture.administration();
            var purge = fixture.preview(
                    DestructiveOperationType.PURGE_DEFINITION, seed, null);
            var delete = fixture.preview(
                    DestructiveOperationType.DELETE_DEFINITION, seed, null);

            var started = administration.start(new StartRequest(
                            purge, "admin", IDEMPOTENCY_KEY))
                    .toCompletableFuture().join();
            var mismatched = administration.start(new StartRequest(
                            delete, "admin", IDEMPOTENCY_KEY))
                    .toCompletableFuture().join();

            assertEquals(StartStatus.STARTED, started.status());
            assertEquals(StartStatus.REJECTED, mismatched.status());
            assertFalse(fixture.definitionDeleted(seed.definitionId()));
            assertEquals(0L, fixture.deletedMarkerCount());
            assertEquals(1L, fixture.destructiveTargetCount());
        }
    }
}
