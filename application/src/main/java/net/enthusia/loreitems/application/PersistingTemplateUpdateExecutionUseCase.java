package net.enthusia.loreitems.application;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

public final class PersistingTemplateUpdateExecutionUseCase
        implements TemplateUpdateExecutionUseCase {
    private static final int MAX_REASON_LENGTH = 4_000;
    private static final int MAX_FINGERPRINT_LENGTH = 256;

    private final TemplateUpdateExecutionStore store;
    private final Clock clock;
    private final Duration claimLease;

    public PersistingTemplateUpdateExecutionUseCase(
            TemplateUpdateExecutionStore store,
            Clock clock,
            Duration claimLease) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.claimLease = Objects.requireNonNull(claimLease, "claimLease");
        if (claimLease.isZero() || claimLease.isNegative()) {
            throw new IllegalArgumentException("claimLease must be positive");
        }
    }

    @Override
    public CompletionStage<TemplateUpdatePrepareResult> prepare(
            LoreItemIdentity observedIdentity) {
        Objects.requireNonNull(observedIdentity, "observedIdentity");
        return store.prepareTemplateUpdate(
                observedIdentity,
                UUID.randomUUID().toString(),
                clock.instant(),
                claimLease);
    }

    @Override
    public CompletionStage<Boolean> release(
            PreparedTemplateUpdate update,
            String reason) {
        return store.releaseTemplateUpdate(
                Objects.requireNonNull(update, "update"),
                normalizeReason(reason),
                clock.instant());
    }

    @Override
    public CompletionStage<Boolean> complete(
            PreparedTemplateUpdate update,
            String beforeFingerprint,
            String afterFingerprint) {
        return store.completeTemplateUpdate(
                Objects.requireNonNull(update, "update"),
                normalizeFingerprint(beforeFingerprint, "beforeFingerprint"),
                normalizeFingerprint(afterFingerprint, "afterFingerprint"),
                clock.instant());
    }

    @Override
    public CompletionStage<Boolean> requireReview(
            PreparedTemplateUpdate update,
            String reason,
            String beforeFingerprint,
            String afterFingerprint) {
        return store.requireTemplateUpdateReview(
                Objects.requireNonNull(update, "update"),
                normalizeReason(reason),
                normalizeOptionalFingerprint(beforeFingerprint, "beforeFingerprint"),
                normalizeOptionalFingerprint(afterFingerprint, "afterFingerprint"),
                clock.instant());
    }

    private static String normalizeReason(String reason) {
        Objects.requireNonNull(reason, "reason");
        String normalized = reason.strip();
        if (normalized.isEmpty() || normalized.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("Invalid template-update reason");
        }
        return normalized;
    }

    private static String normalizeFingerprint(String fingerprint, String name) {
        Objects.requireNonNull(fingerprint, name);
        String normalized = fingerprint.strip();
        if (normalized.isEmpty() || normalized.length() > MAX_FINGERPRINT_LENGTH) {
            throw new IllegalArgumentException("Invalid " + name);
        }
        return normalized;
    }

    private static String normalizeOptionalFingerprint(String fingerprint, String name) {
        if (fingerprint == null) {
            return null;
        }
        return normalizeFingerprint(fingerprint, name);
    }
}
