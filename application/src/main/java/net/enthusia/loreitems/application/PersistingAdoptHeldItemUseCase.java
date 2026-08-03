package net.enthusia.loreitems.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

public final class PersistingAdoptHeldItemUseCase implements AdoptHeldItemUseCase {
    private final HeldItemAdoptionStore store;
    private final Clock clock;
    private final Duration claimLease;
    private final Supplier<UUID> mutationIdSupplier;
    private final Supplier<UUID> instanceIdSupplier;
    private final Supplier<UUID> claimTokenSupplier;

    public PersistingAdoptHeldItemUseCase(
            HeldItemAdoptionStore store,
            Clock clock,
            Duration claimLease) {
        this(store, clock, claimLease, UUID::randomUUID, UUID::randomUUID, UUID::randomUUID);
    }

    PersistingAdoptHeldItemUseCase(
            HeldItemAdoptionStore store,
            Clock clock,
            Duration claimLease,
            Supplier<UUID> mutationIdSupplier,
            Supplier<UUID> instanceIdSupplier,
            Supplier<UUID> claimTokenSupplier) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.claimLease = Objects.requireNonNull(claimLease, "claimLease");
        if (claimLease.isZero() || claimLease.isNegative()) {
            throw new IllegalArgumentException("claimLease must be positive");
        }
        this.mutationIdSupplier = Objects.requireNonNull(
                mutationIdSupplier, "mutationIdSupplier");
        this.instanceIdSupplier = Objects.requireNonNull(
                instanceIdSupplier, "instanceIdSupplier");
        this.claimTokenSupplier = Objects.requireNonNull(
                claimTokenSupplier, "claimTokenSupplier");
    }

    @Override
    public CompletionStage<PrepareHeldItemAdoptionResult> prepare(
            PrepareHeldItemAdoptionRequest request) {
        Objects.requireNonNull(request, "request");
        Instant preparedAt = clock.instant();
        long preparedAtMillis = preparedAt.toEpochMilli();
        long expiresAtMillis = preparedAt.plus(claimLease).toEpochMilli();
        HeldItemAdoptionPreparation preparation = new HeldItemAdoptionPreparation(
                request,
                generated(mutationIdSupplier, "mutation ID"),
                generated(instanceIdSupplier, "instance ID"),
                generated(claimTokenSupplier, "claim token"),
                preparedAtMillis,
                expiresAtMillis);
        return store.prepare(preparation).thenApply(PersistingAdoptHeldItemUseCase::result);
    }

    @Override
    public CompletionStage<Boolean> complete(
            PreparedHeldItemAdoption adoption,
            String afterFingerprint) {
        Objects.requireNonNull(adoption, "adoption");
        Objects.requireNonNull(afterFingerprint, "afterFingerprint");
        return store.complete(adoption, afterFingerprint, clock.instant());
    }

    @Override
    public CompletionStage<Boolean> requireReview(
            PreparedHeldItemAdoption adoption,
            String reason) {
        Objects.requireNonNull(adoption, "adoption");
        Objects.requireNonNull(reason, "reason");
        String normalizedReason = reason.strip();
        if (normalizedReason.isEmpty()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        return store.requireReview(adoption, normalizedReason, clock.instant());
    }

    private static PrepareHeldItemAdoptionResult result(
            Optional<PreparedHeldItemAdoption> adoption) {
        Objects.requireNonNull(adoption, "adoption");
        return adoption.map(PrepareHeldItemAdoptionResult::prepared)
                .orElseGet(PrepareHeldItemAdoptionResult::unknownDefinition);
    }

    private static UUID generated(Supplier<UUID> supplier, String description) {
        return Objects.requireNonNull(supplier.get(), "generated " + description);
    }
}
