package net.enthusia.loreitems.paper;

import java.util.List;
import java.util.Objects;

public record GroupFileValidationFailure(String sourceName, List<String> diagnostics) {
    public GroupFileValidationFailure {
        sourceName = Objects.requireNonNull(sourceName, "sourceName").strip();
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        if (sourceName.isEmpty() || diagnostics.isEmpty()) {
            throw new IllegalArgumentException("Validation failure requires source and diagnostics");
        }
    }
}
