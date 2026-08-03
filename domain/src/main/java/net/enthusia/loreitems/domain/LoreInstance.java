package net.enthusia.loreitems.domain;

import java.util.Objects;

public record LoreInstance(
        LoreInstanceId id,
        LoreDefinitionId definitionId,
        TemplateRevision appliedRevision,
        TemplateRevision desiredRevision,
        LoreInstanceLifecycle lifecycle,
        long createdAtEpochMillis,
        Long terminalAtEpochMillis) {
    private static final long MIN_TIMESTAMP = 0L;

    public LoreInstance {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(definitionId, "definitionId");
        Objects.requireNonNull(appliedRevision, "appliedRevision");
        Objects.requireNonNull(desiredRevision, "desiredRevision");
        Objects.requireNonNull(lifecycle, "lifecycle");
        if (desiredRevision.compareTo(appliedRevision) < 0) {
            throw new IllegalArgumentException("desiredRevision must not precede appliedRevision");
        }
        if (createdAtEpochMillis < MIN_TIMESTAMP) {
            throw new IllegalArgumentException("createdAtEpochMillis must not be negative");
        }
        if (lifecycle.terminal() != (terminalAtEpochMillis != null)) {
            throw new IllegalArgumentException("Terminal lifecycle and terminal timestamp must agree");
        }
        if (terminalAtEpochMillis != null && terminalAtEpochMillis < createdAtEpochMillis) {
            throw new IllegalArgumentException("terminalAtEpochMillis must not precede creation");
        }
    }
}
