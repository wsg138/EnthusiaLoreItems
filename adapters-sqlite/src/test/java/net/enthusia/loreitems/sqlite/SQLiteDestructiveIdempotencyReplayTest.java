package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.Preview;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.StartRequest;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase.StartStatus;
import net.enthusia.loreitems.domain.DestructiveOperationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SQLiteDestructiveIdempotencyReplayTest {
    private static final Instant NOW = Instant.ofEpochMilli(2_000L);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String IDEMPOTENCY_KEY = "shared-destructive-key";
    private static final String ACTOR = "admin";

    @TempDir
    Path temporaryDirectory;

    @Test
    void reusedKeyCannotAliasDifferentDestructiveConfirmation() {
        try (SQLiteDestructiveTestFixture fixture = fixture("replay.db")) {
            var seed = fixture.seed(true);
            var purge = fixture.preview(
                    DestructiveOperationType.PURGE_DEFINITION, seed, null);
            var delete = fixture.preview(
                    DestructiveOperationType.DELETE_DEFINITION, seed, null);

            assertEquals(StartStatus.STARTED, start(fixture, purge));
            assertEquals(StartStatus.REJECTED, start(fixture, delete));
            assertRejectedReplayState(fixture, seed);
        }
    }

    @Test
    void reusedKeyCannotAliasChangedTargetSnapshot() {
        try (SQLiteDestructiveTestFixture fixture = fixture("snapshot-replay.db")) {
            var seed = fixture.seed(true);
            var original = fixture.preview(
                    DestructiveOperationType.PURGE_DEFINITION, seed, null);
            assertEquals(StartStatus.STARTED, start(fixture, original));

            fixture.moveCurrentState(seed.instanceId(), "player:moved-after-acceptance");
            var changed = fixture.preview(
                    DestructiveOperationType.PURGE_DEFINITION, seed, null);

            assertEquals(StartStatus.REJECTED, start(fixture, changed));
            assertRejectedReplayState(fixture, seed);
        }
    }

    private SQLiteDestructiveTestFixture fixture(String fileName) {
        return new SQLiteDestructiveTestFixture(temporaryDirectory, fileName, CLOCK);
    }

    private static StartStatus start(SQLiteDestructiveTestFixture fixture, Preview preview) {
        return fixture.administration().start(new StartRequest(preview, ACTOR, IDEMPOTENCY_KEY))
                .toCompletableFuture().join().status();
    }

    private static void assertRejectedReplayState(
            SQLiteDestructiveTestFixture fixture,
            SQLiteDestructiveTestFixture.Seed seed) {
        assertFalse(fixture.definitionDeleted(seed.definitionId()));
        assertEquals(0L, fixture.deletedMarkerCount());
        assertEquals(1L, fixture.destructiveTargetCount());
    }
}
