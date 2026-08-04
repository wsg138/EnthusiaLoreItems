package net.enthusia.loreitems.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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

class PersistingVoidLossUseCaseTest {
    private static final UUID DEFINITION_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID INSTANCE_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ENTITY_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID MUTATION_ID =
            UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID CLAIM_TOKEN =
            UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final long NOW = 10_000L;

    @Test
    void preparesDurableIntentWithFreshClaimAndBoundedLease() {
        RecordingStore store = new RecordingStore();
        PersistingVoidLossUseCase useCase = useCase(store);
        VoidLossUseCase.Request request = request();

        VoidLossUseCase.PrepareResult result = useCase.prepare(request)
                .toCompletableFuture().join();

        assertEquals(VoidLossUseCase.PrepareStatus.PREPARED, result.status());
        assertSame(request, store.request);
        assertEquals(MUTATION_ID, store.mutationId);
        assertEquals(CLAIM_TOKEN, store.claimToken);
        assertEquals(NOW, store.preparedAt.toEpochMilli());
        assertEquals(NOW + 30_000L, store.claimExpiresAt.toEpochMilli());
        assertEquals(MUTATION_ID, result.prepared().mutationId());
    }

    @Test
    void delegatesCompletionAbortAndReviewWithNormalizedReasons() {
        RecordingStore store = new RecordingStore();
        PersistingVoidLossUseCase useCase = useCase(store);
        PreparedVoidLoss loss = useCase.prepare(request())
                .toCompletableFuture().join().prepared();

        assertTrue(useCase.complete(loss).toCompletableFuture().join());
        assertSame(loss, store.completedLoss);
        assertEquals(NOW, store.completedAt.toEpochMilli());

        assertTrue(useCase.abort(loss, " rescued above void ")
                .toCompletableFuture().join());
        assertSame(loss, store.abortedLoss);
        assertEquals("rescued above void", store.abortReason);
        assertEquals(NOW, store.abortedAt.toEpochMilli());

        assertTrue(useCase.requireReview(loss, " entity vanished ")
                .toCompletableFuture().join());
        assertSame(loss, store.reviewedLoss);
        assertEquals("entity vanished", store.reviewReason);
        assertEquals(NOW, store.reviewedAt.toEpochMilli());
    }

    private static PersistingVoidLossUseCase useCase(RecordingStore store) {
        return new PersistingVoidLossUseCase(
                store,
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC),
                Duration.ofSeconds(30),
                () -> MUTATION_ID,
                () -> CLAIM_TOKEN);
    }

    private static VoidLossUseCase.Request request() {
        return new VoidLossUseCase.Request(
                new LoreItemIdentity(
                        new LoreDefinitionId(DEFINITION_ID),
                        new LoreInstanceId(INSTANCE_ID),
                        new TemplateRevision(7)),
                ENTITY_ID,
                "minecraft:overworld:12:-65:34");
    }

    private static final class RecordingStore implements VoidLossStore {
        private VoidLossUseCase.Request request;
        private UUID mutationId;
        private UUID claimToken;
        private Instant preparedAt;
        private Instant claimExpiresAt;
        private PreparedVoidLoss completedLoss;
        private Instant completedAt;
        private PreparedVoidLoss abortedLoss;
        private String abortReason;
        private Instant abortedAt;
        private PreparedVoidLoss reviewedLoss;
        private String reviewReason;
        private Instant reviewedAt;

        @Override
        public CompletionStage<VoidLossUseCase.PrepareResult> prepare(
                VoidLossUseCase.Request candidate,
                UUID candidateMutationId,
                UUID candidateClaimToken,
                Instant candidatePreparedAt,
                Instant candidateClaimExpiresAt) {
            request = candidate;
            mutationId = candidateMutationId;
            claimToken = candidateClaimToken;
            preparedAt = candidatePreparedAt;
            claimExpiresAt = candidateClaimExpiresAt;
            PreparedVoidLoss loss = new PreparedVoidLoss(
                    candidateMutationId,
                    candidate.identity(),
                    candidate.entityId(),
                    candidate.locationKey(),
                    candidateClaimToken,
                    candidatePreparedAt.toEpochMilli(),
                    candidateClaimExpiresAt.toEpochMilli());
            return CompletableFuture.completedFuture(VoidLossUseCase.PrepareResult.prepared(loss));
        }

        @Override
        public CompletionStage<Boolean> complete(PreparedVoidLoss loss, Instant at) {
            completedLoss = loss;
            completedAt = at;
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletionStage<Boolean> abort(
                PreparedVoidLoss loss,
                String reason,
                Instant at) {
            abortedLoss = loss;
            abortReason = reason;
            abortedAt = at;
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletionStage<Boolean> requireReview(
                PreparedVoidLoss loss,
                String reason,
                Instant at) {
            reviewedLoss = loss;
            reviewReason = reason;
            reviewedAt = at;
            return CompletableFuture.completedFuture(true);
        }
    }
}
