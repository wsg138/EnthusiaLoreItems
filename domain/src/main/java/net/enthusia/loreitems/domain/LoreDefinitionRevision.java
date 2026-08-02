package net.enthusia.loreitems.domain;

import java.util.Arrays;
import java.util.Objects;

public record LoreDefinitionRevision(
        LoreDefinitionId definitionId,
        TemplateRevision revision,
        int codecVersion,
        byte[] templateBlob,
        long createdAtEpochMillis) {
    public static final int MAX_TEMPLATE_BYTES = 4 * 1024 * 1024;

    public LoreDefinitionRevision {
        Objects.requireNonNull(definitionId, "definitionId");
        Objects.requireNonNull(revision, "revision");
        Objects.requireNonNull(templateBlob, "templateBlob");
        if (codecVersion < 1) {
            throw new IllegalArgumentException("codecVersion must be positive");
        }
        if (templateBlob.length < 1 || templateBlob.length > MAX_TEMPLATE_BYTES) {
            throw new IllegalArgumentException("templateBlob must contain 1-4194304 bytes");
        }
        if (createdAtEpochMillis < 0L) {
            throw new IllegalArgumentException("createdAtEpochMillis must not be negative");
        }
        templateBlob = templateBlob.clone();
    }

    @Override
    public byte[] templateBlob() {
        return templateBlob.clone();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof LoreDefinitionRevision that)) {
            return false;
        }
        return codecVersion == that.codecVersion
                && createdAtEpochMillis == that.createdAtEpochMillis
                && definitionId.equals(that.definitionId)
                && revision.equals(that.revision)
                && Arrays.equals(templateBlob, that.templateBlob);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(definitionId, revision, codecVersion, createdAtEpochMillis);
        return 31 * result + Arrays.hashCode(templateBlob);
    }

    @Override
    public String toString() {
        return "LoreDefinitionRevision[definitionId=" + definitionId
                + ", revision=" + revision
                + ", codecVersion=" + codecVersion
                + ", templateBytes=" + templateBlob.length
                + ", createdAtEpochMillis=" + createdAtEpochMillis + ']';
    }
}
