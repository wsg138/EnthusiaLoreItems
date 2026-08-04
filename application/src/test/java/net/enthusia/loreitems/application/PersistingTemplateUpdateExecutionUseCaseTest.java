package net.enthusia.loreitems.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.junit.jupiter.api.Test;

class PersistingTemplateUpdateExecutionUseCaseTest {
    private static final Instant NOW = Instant.ofEpochMilli(4_000L);
    private static final Duration LEASE = Duration.ofSeconds(30L);

    @Test
    void delegatesAClockedClaimAndPhysicalCompletionEvidence() {
        RecordingStore store = new RecordingStore();
        PersistingTemplateUpdateExecutionUseCase useCase =
                new PersistingTemplateUpdateExecutionUseCase(
                        store,
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        LEASE);
        LoreItemIdentity observed = identity(1L);

        TemplateUpdatePrepareResult prepared = useCase.prepare(observed)
                .toCompletableFuture().join();
        assertEquals(TemplateUpdatePrepareResult.Status.PREPARED, prepared.status());
        assertEquals(observed, store.observedIdentity);
        assertNotNull(store.claimToken);
        assertFalse(store.claimToken.isBlank());
        assertEquals(NOW, store.now);
        assertEquals(LEASE, store.lease);

        PreparedTemplateUpdate update = prepared.preparedUpdate();
        assertTrue(useCase.complete(update, " before ", " after ")
                .toCompletableFuture().join());
        assertEquals("before", store.beforeFingerprint);
        assertEquals("after", store.afterFingerprint);
        assertEquals(NOW, store.now);
    }

    @Test
    void rejectsBlankReviewReasonsBeforeTheyReachStorage() {
        PersistingTemplateUpdateExecutionUseCase useCase =
                new PersistingTemplateUpdateExecutionUseCase(
                        new RecordingStore(),
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        LEASE);

        assertThrows(
                IllegalArgumentException.class,
                () -> useCase.requireReview(prepared(), " ", null, null));
    }

    private static PreparedTemplateUpdate prepared() {
        return new PreparedTemplateUpdate(
                UUID.randomUUID(),
                "claim",
                identity(1L),
                identity(2L),
                new EncodedItemTemplate(1, new byte[] {2}),
                34_000L);
    }

    private static LoreItemIdentity identity(long revision) {
        return IdentityHolder.identity(revision);
    }

    private static final class IdentityHolder {
        private static final LoreDefinitionId DEFINITION_ID =
                new LoreDefinitionId(UUID.randomUUID());
        private static final LoreInstanceId INSTANCE_ID =
                new LoreInstanceId(UUID.randomUUID());

        private static LoreItemIdentity identity(long revision) {
            return new LoreItemIdentity(
                    DEFINITION_ID,
                    INSTANCE_ID,
                    new TemplateRevision(revision));
        }
    }

    private static final class RecordingStore implements TemplateUpdateExecutionStore {
        private LoreItemIdentity observedIdentity;
        private String claimToken;
        private Instant now;
        private Duration lease;
        private String beforeFingerprint;
        private String afterFingerprint;

        @Override
        public CompletionStage<TemplateUpdatePrepareResult> prepareTemplateUpdate(
                LoreItemIdentity observedIdentity,
                String claimToken,
                Instant now,
                Duration lease) {
            this.observedIdentity = observedIdentity;
            this.claimToken = claimToken;
            this.now = now;
            this.lease = lease;
            return CompletableFuture.completedFuture(
                    TemplateUpdatePrepareResult.prepared(prepared()));
        }

        @Override
        public CompletionStage<Boolean> releaseTemplateUpdate(
                PreparedTemplateUpdate update,
                String reason,
                Instant now) {
            this.now = now;
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletionStage<Boolean> completeTemplateUpdate(
                PreparedTemplateUpdate update,
                String beforeFingerprint,
                String afterFingerprint,
                Instant now) {
            this.beforeFingerprint = beforeFingerprint;
            this.afterFingerprint = afterFingerprint;
            this.now = now;
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletionStage<Boolean> requireTemplateUpdateReview(
                PreparedTemplateUpdate update,
                String reason,
                String beforeFingerprint,
                String afterFingerprint,
                Instant now) {
            this.now = now;
            return CompletableFuture.completedFuture(true);
        }
    }
}
