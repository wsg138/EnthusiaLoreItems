package net.enthusia.loreitems.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.enthusia.loreitems.api.v1.LoreDeliveryResult;
import net.enthusia.loreitems.api.v1.LoreDeliveryStatus;
import net.enthusia.loreitems.application.ExternalDeliveryCommand;
import net.enthusia.loreitems.application.ExternalDeliveryOutcome;
import net.enthusia.loreitems.application.ExternalDeliveryUseCase;
import org.junit.jupiter.api.Test;

class FoundationLoreItemsServiceTest {
    private static final UUID PLAYER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void mapsEveryDurableOutcomeWithoutChangingTheExternalIdempotencyKey() {
        for (ExternalDeliveryOutcome outcome : ExternalDeliveryOutcome.values()) {
            FoundationLoreItemsService service = new FoundationLoreItemsService(command ->
                    CompletableFuture.completedFuture(outcome));

            LoreDeliveryResult result = service.queueDelivery(
                            "starter_blade", PLAYER_ID, " tags:claim-42 ")
                    .toCompletableFuture()
                    .join();

            assertEquals(LoreDeliveryStatus.valueOf(outcome.name()), result.status());
            assertEquals("tags:claim-42", result.externalOperationId());
        }
    }

    @Test
    void repeatedExternalOperationReturnsTheUseCasesStoredReplayOutcome() {
        ExternalDeliveryUseCase useCase = new ExternalDeliveryUseCase() {
            private boolean accepted;

            @Override
            public java.util.concurrent.CompletionStage<ExternalDeliveryOutcome> queue(
                    ExternalDeliveryCommand command) {
                ExternalDeliveryOutcome outcome = accepted
                        ? ExternalDeliveryOutcome.ALREADY_ACCEPTED
                        : ExternalDeliveryOutcome.ACCEPTED_QUEUED;
                accepted = true;
                return CompletableFuture.completedFuture(outcome);
            }
        };
        FoundationLoreItemsService service = new FoundationLoreItemsService(useCase);

        LoreDeliveryResult first = service.queueDelivery(
                        "starter_blade", PLAYER_ID, "tags:claim-43")
                .toCompletableFuture()
                .join();
        LoreDeliveryResult replay = service.queueDelivery(
                        "starter_blade", PLAYER_ID, "tags:claim-43")
                .toCompletableFuture()
                .join();

        assertEquals(LoreDeliveryStatus.ACCEPTED_QUEUED, first.status());
        assertEquals(LoreDeliveryStatus.ALREADY_ACCEPTED, replay.status());
        assertEquals(first.externalOperationId(), replay.externalOperationId());
    }

    @Test
    void invalidInputsReturnValidationFailureInsteadOfThrowingAcrossTheApiBoundary() {
        FoundationLoreItemsService service = new FoundationLoreItemsService(command ->
                CompletableFuture.completedFuture(ExternalDeliveryOutcome.ACCEPTED_QUEUED));

        LoreDeliveryResult blankDefinition = service.queueDelivery(
                        " ", PLAYER_ID, "operation")
                .toCompletableFuture()
                .join();
        LoreDeliveryResult missingPlayer = service.queueDelivery(
                        "starter_blade", null, "operation")
                .toCompletableFuture()
                .join();
        LoreDeliveryResult missingOperation = service.queueDelivery(
                        "starter_blade", PLAYER_ID, null)
                .toCompletableFuture()
                .join();

        assertEquals(LoreDeliveryStatus.VALIDATION_FAILURE, blankDefinition.status());
        assertEquals(LoreDeliveryStatus.VALIDATION_FAILURE, missingPlayer.status());
        assertEquals(LoreDeliveryStatus.VALIDATION_FAILURE, missingOperation.status());
    }

    @Test
    void durableStorageFailureReturnsServiceUnavailable() {
        FoundationLoreItemsService service = new FoundationLoreItemsService(command ->
                CompletableFuture.failedFuture(new IllegalStateException("storage unavailable")));

        LoreDeliveryResult result = service.queueDelivery(
                        "starter_blade", PLAYER_ID, "tags:claim-44")
                .toCompletableFuture()
                .join();

        assertEquals(LoreDeliveryStatus.SERVICE_UNAVAILABLE, result.status());
        assertEquals("tags:claim-44", result.externalOperationId());
    }
}
