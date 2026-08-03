package net.enthusia.loreitems.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.domain.DefinitionKey;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.junit.jupiter.api.Test;

class PersistingAdoptHeldItemUseCaseTest {
    private static final UUID PLAYER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MUTATION_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID INSTANCE_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID CLAIM_TOKEN =
            UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID DEFINITION_ID =
            UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final String BEFORE_FINGERPRINT = "a".repeat(64);
    private static final String AFTER_FINGERPRINT = "b".repeat(64);
    private static final long NOW = 10_000L;

    @Test
    void preparesFreshDurableIdentityWithBoundedLease() {
        RecordingStore store = new RecordingStore(true);
        PersistingAdoptHeldItemUseCase useCase = useCase(store);
        PrepareHeldItemAdoptionRequest request = request();

        PrepareHeldItemAdoptionResult result = useCase.prepare(request)
                .toCompletableFuture().join();

        assertEquals(PrepareHeldItemAdoptionResult.Status.PREPARED, result.status());
        HeldItemAdoptionPreparation preparation = store.preparation;
        assertSame(request, preparation.request());
        assertEquals(MUTATION_ID, preparation.mutationId());
        assertEquals(INSTANCE_ID, preparation.instanceId());
        assertEquals(CLAIM_TOKEN, preparation.claimToken());
        assertEquals(NOW, preparation.preparedAtEpochMillis());
        assertEquals(NOW + 30_000L, preparation.claimExpiresAtEpochMillis());
        assertEquals(new LoreInstanceId(INSTANCE_ID),
                result.preparedAdoption().instanceId());
    }

    @Test
    void reportsUnknownDefinitionWithoutInventingPreparedResult() {
        RecordingStore store = new RecordingStore(false);

        PrepareHeldItemAdoptionResult result = useCase(store).prepare(request())
                .toCompletableFuture().join();

        assertEquals(PrepareHeldItemAdoptionResult.Status.UNKNOWN_DEFINITION, result.status());
        assertEquals(null, result.preparedAdoption());
    }

    @Test
    void delegatesCompletionAndReviewUsingThePreparedClaim() {
        RecordingStore store = new RecordingStore(true);
        PersistingAdoptHeldItemUseCase useCase = useCase(store);
        PreparedHeldItemAdoption adoption = useCase.prepare(request())
                .toCompletableFuture().join().preparedAdoption();

        assertTrue(useCase.complete(adoption, AFTER_FINGERPRINT)
                .toCompletableFuture().join());
        assertSame(adoption, store.completedAdoption);
        assertEquals(AFTER_FINGERPRINT, store.afterFingerprint);
        assertEquals(NOW, store.completedAt.toEpochMilli());

        assertTrue(useCase.requireReview(adoption, " slot changed ")
                .toCompletableFuture().join());
        assertSame(adoption, store.reviewedAdoption);
        assertEquals("slot changed", store.reviewReason);
        assertEquals(NOW, store.reviewedAt.toEpochMilli());
    }

    private static PersistingAdoptHeldItemUseCase useCase(RecordingStore store) {
        return new PersistingAdoptHeldItemUseCase(
                store,
                Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC),
                Duration.ofSeconds(30),
                () -> MUTATION_ID,
                () -> INSTANCE_ID,
                () -> CLAIM_TOKEN);
    }

    private static PrepareHeldItemAdoptionRequest request() {
        return new PrepareHeldItemAdoptionRequest(
                new DefinitionKey("vanguards_hourglass"),
                PLAYER_ID,
                4,
                BEFORE_FINGERPRINT);
    }

    private static final class RecordingStore implements HeldItemAdoptionStore {
        private final boolean definitionExists;
        private HeldItemAdoptionPreparation preparation;
        private PreparedHeldItemAdoption completedAdoption;
        private String afterFingerprint;
        private Instant completedAt;
        private PreparedHeldItemAdoption reviewedAdoption;
        private String reviewReason;
        private Instant reviewedAt;

        private RecordingStore(boolean definitionExists) {
            this.definitionExists = definitionExists;
        }

        @Override
        public CompletionStage<Optional<PreparedHeldItemAdoption>> prepare(
                HeldItemAdoptionPreparation candidate) {
            preparation = candidate;
            if (!definitionExists) {
                return CompletableFuture.completedFuture(Optional.empty());
            }
            PreparedHeldItemAdoption prepared = new PreparedHeldItemAdoption(
                    candidate.mutationId(),
                    candidate.request().definitionKey(),
                    new LoreDefinitionId(DEFINITION_ID),
                    new LoreInstanceId(candidate.instanceId()),
                    new TemplateRevision(7),
                    candidate.request().playerId(),
                    candidate.request().selectedSlot(),
                    candidate.request().beforeFingerprint(),
                    candidate.claimToken(),
                    candidate.preparedAtEpochMillis(),
                    candidate.claimExpiresAtEpochMillis());
            return CompletableFuture.completedFuture(Optional.of(prepared));
        }

        @Override
        public CompletionStage<Boolean> complete(
                PreparedHeldItemAdoption adoption,
                String fingerprint,
                Instant at) {
            completedAdoption = adoption;
            afterFingerprint = fingerprint;
            completedAt = at;
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletionStage<Boolean> requireReview(
                PreparedHeldItemAdoption adoption,
                String reason,
                Instant at) {
            reviewedAdoption = adoption;
            reviewReason = reason;
            reviewedAt = at;
            return CompletableFuture.completedFuture(true);
        }
    }
}
