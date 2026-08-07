package net.enthusia.loreitems.paper;

import java.util.List;
import java.util.Objects;

public record GroupFileDefinition(
        String sourceName,
        String displayName,
        String sourceFingerprint,
        List<GroupFileRecipient> recipients) {
    public GroupFileDefinition {
        sourceName = requireText(sourceName, "sourceName");
        displayName = requireText(displayName, "displayName");
        sourceFingerprint = requireText(sourceFingerprint, "sourceFingerprint");
        recipients = List.copyOf(Objects.requireNonNull(recipients, "recipients"));
        if (recipients.isEmpty()) {
            throw new IllegalArgumentException("recipients must not be empty");
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
