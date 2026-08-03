package net.enthusia.loreitems.application;

import java.util.Arrays;
import java.util.Objects;
import net.enthusia.loreitems.domain.LoreDefinitionRevision;

public record EncodedItemTemplate(int codecVersion, byte[] payload) {
    public EncodedItemTemplate {
        Objects.requireNonNull(payload, "payload");
        if (codecVersion < 1) {
            throw new IllegalArgumentException("codecVersion must be positive");
        }
        if (payload.length < 1 || payload.length > LoreDefinitionRevision.MAX_TEMPLATE_BYTES) {
            throw new IllegalArgumentException("payload must contain 1-4194304 bytes");
        }
        payload = payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof EncodedItemTemplate that
                && codecVersion == that.codecVersion
                && Arrays.equals(payload, that.payload);
    }

    @Override
    public int hashCode() {
        return 31 * Integer.hashCode(codecVersion) + Arrays.hashCode(payload);
    }

    @Override
    public String toString() {
        return "EncodedItemTemplate[codecVersion=" + codecVersion + ", payloadBytes=" + payload.length + ']';
    }
}
