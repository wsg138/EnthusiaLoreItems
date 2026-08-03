package net.enthusia.loreitems.application;

import java.util.Objects;

public sealed interface ItemIdentityReadResult
        permits ItemIdentityReadResult.Untracked,
                ItemIdentityReadResult.Tracked,
                ItemIdentityReadResult.Invalid {
    record Untracked() implements ItemIdentityReadResult {}

    record Tracked(LoreItemIdentity identity) implements ItemIdentityReadResult {
        public Tracked {
            Objects.requireNonNull(identity, "identity");
        }
    }

    record Invalid(ItemIdentityFailure failure, String detail) implements ItemIdentityReadResult {
        public Invalid {
            Objects.requireNonNull(failure, "failure");
            Objects.requireNonNull(detail, "detail");
            if (detail.isBlank()) {
                throw new IllegalArgumentException("detail must not be blank");
            }
        }
    }
}
