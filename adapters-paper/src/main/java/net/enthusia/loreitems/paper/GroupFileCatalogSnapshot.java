package net.enthusia.loreitems.paper;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record GroupFileCatalogSnapshot(
        List<GroupFileDefinition> validFiles,
        List<GroupFileValidationFailure> invalidFiles) {
    public GroupFileCatalogSnapshot {
        validFiles = List.copyOf(Objects.requireNonNull(validFiles, "validFiles"));
        invalidFiles = List.copyOf(Objects.requireNonNull(invalidFiles, "invalidFiles"));
    }

    public Optional<GroupFileDefinition> validFile(String sourceName) {
        Objects.requireNonNull(sourceName, "sourceName");
        return validFiles.stream()
                .filter(file -> file.sourceName().equals(sourceName))
                .findFirst();
    }
}
