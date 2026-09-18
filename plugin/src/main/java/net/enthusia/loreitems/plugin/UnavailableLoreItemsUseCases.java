package net.enthusia.loreitems.plugin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.AdoptHeldItemUseCase;
import net.enthusia.loreitems.application.CreateDefinitionResult;
import net.enthusia.loreitems.application.CreateDefinitionUseCase;
import net.enthusia.loreitems.application.DisplayItemObservationUseCase;
import net.enthusia.loreitems.application.PrepareHeldItemAdoptionRequest;
import net.enthusia.loreitems.application.PrepareHeldItemAdoptionResult;
import net.enthusia.loreitems.application.PreparedHeldItemAdoption;
import net.enthusia.loreitems.application.PreparedVoidLoss;
import net.enthusia.loreitems.application.VoidLossUseCase;

/** Fail-closed use cases published before durable storage becomes writable. */
final class UnavailableLoreItemsUseCases {
    private UnavailableLoreItemsUseCases() {}

    static CreateDefinitionUseCase createDefinition() {
        return request -> CompletableFuture.completedFuture(
                CreateDefinitionResult.serviceUnavailable());
    }

    static AdoptHeldItemUseCase adoptHeldItem() {
        return new AdoptHeldItemUseCase() {
            @Override
            public CompletionStage<PrepareHeldItemAdoptionResult> prepare(
                    PrepareHeldItemAdoptionRequest request) {
                return CompletableFuture.completedFuture(
                        PrepareHeldItemAdoptionResult.serviceUnavailable());
            }

            @Override
            public CompletionStage<Boolean> complete(
                    PreparedHeldItemAdoption adoption,
                    String afterFingerprint) {
                return CompletableFuture.completedFuture(false);
            }

            @Override
            public CompletionStage<Boolean> requireReview(
                    PreparedHeldItemAdoption adoption,
                    String reason) {
                return CompletableFuture.completedFuture(false);
            }
        };
    }

    static VoidLossUseCase voidLoss() {
        return new VoidLossUseCase() {
            @Override
            public CompletionStage<PrepareResult> prepare(Request request) {
                return CompletableFuture.completedFuture(PrepareResult.of(
                        PrepareStatus.SERVICE_UNAVAILABLE,
                        "Durable storage is unavailable; the item remains protected."));
            }

            @Override
            public CompletionStage<Boolean> complete(PreparedVoidLoss loss) {
                return CompletableFuture.completedFuture(false);
            }

            @Override
            public CompletionStage<Boolean> abort(PreparedVoidLoss loss, String reason) {
                return CompletableFuture.completedFuture(false);
            }

            @Override
            public CompletionStage<Boolean> requireReview(
                    PreparedVoidLoss loss,
                    String reason) {
                return CompletableFuture.completedFuture(false);
            }
        };
    }

    static DisplayItemObservationUseCase displayObservation() {
        return request -> CompletableFuture.completedFuture(
                DisplayItemObservationUseCase.Result.of(
                        DisplayItemObservationUseCase.Status.SERVICE_UNAVAILABLE,
                        "Durable storage is unavailable; display evidence was not changed."));
    }
}
