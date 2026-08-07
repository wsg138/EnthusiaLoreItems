CREATE TABLE destructive_operations (
    operation_id TEXT PRIMARY KEY,
    operation_type TEXT NOT NULL CHECK (
        operation_type IN (
            'EXACT_INSTANCE_REMOVAL',
            'PURGE_DEFINITION',
            'DELETE_DEFINITION'
        )
    ),
    definition_id TEXT NOT NULL,
    exact_instance_id TEXT,
    expected_revision INTEGER NOT NULL CHECK (expected_revision >= 1),
    state TEXT NOT NULL CHECK (
        state IN ('ACTIVE', 'PAUSED', 'COMPLETED', 'ABORTED')
    ),
    actor_id TEXT NOT NULL CHECK (length(actor_id) BETWEEN 1 AND 200),
    idempotency_key TEXT NOT NULL UNIQUE
        CHECK (length(idempotency_key) BETWEEN 1 AND 240),
    confirmation_token TEXT NOT NULL
        CHECK (length(confirmation_token) BETWEEN 1 AND 128),
    target_count INTEGER NOT NULL CHECK (target_count >= 0),
    accepted_at INTEGER NOT NULL CHECK (accepted_at >= 0),
    updated_at INTEGER NOT NULL CHECK (updated_at >= accepted_at),
    terminal_at INTEGER,
    CHECK (
        (operation_type = 'EXACT_INSTANCE_REMOVAL' AND exact_instance_id IS NOT NULL)
        OR (operation_type <> 'EXACT_INSTANCE_REMOVAL' AND exact_instance_id IS NULL)
    ),
    CHECK (
        (state IN ('ACTIVE', 'PAUSED') AND terminal_at IS NULL)
        OR (state IN ('COMPLETED', 'ABORTED') AND terminal_at IS NOT NULL)
    ),
    CHECK (terminal_at IS NULL OR terminal_at >= updated_at),
    FOREIGN KEY (definition_id) REFERENCES lore_definitions(definition_id),
    FOREIGN KEY (exact_instance_id) REFERENCES lore_instances(instance_id)
);

CREATE INDEX idx_destructive_operations_state
    ON destructive_operations(state, updated_at, operation_id);

CREATE INDEX idx_destructive_operations_definition
    ON destructive_operations(definition_id, accepted_at DESC, operation_id);

CREATE TABLE destructive_targets (
    operation_id TEXT NOT NULL,
    instance_id TEXT NOT NULL,
    definition_id TEXT NOT NULL,
    expected_applied_revision INTEGER NOT NULL CHECK (expected_applied_revision >= 1),
    expected_location_type TEXT,
    expected_location_key TEXT,
    expected_container_path TEXT,
    expected_fingerprint TEXT,
    state TEXT NOT NULL CHECK (
        state IN (
            'PENDING',
            'CLAIMED',
            'APPLIED',
            'VERIFIED',
            'COMPLETED',
            'REVIEW_REQUIRED',
            'ABORTED'
        )
    ),
    effect_state TEXT NOT NULL CHECK (
        effect_state IN ('UNKNOWN', 'NONE_OBSERVED', 'REMOVED_OBSERVED', 'AMBIGUOUS')
    ),
    claim_token TEXT,
    claim_expires_at INTEGER,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    before_fingerprint TEXT,
    after_fingerprint TEXT,
    last_error TEXT,
    created_at INTEGER NOT NULL CHECK (created_at >= 0),
    updated_at INTEGER NOT NULL CHECK (updated_at >= created_at),
    PRIMARY KEY (operation_id, instance_id),
    CHECK (
        (state = 'CLAIMED'
            AND claim_token IS NOT NULL
            AND length(claim_token) BETWEEN 1 AND 200
            AND claim_expires_at IS NOT NULL)
        OR (state <> 'CLAIMED' AND claim_token IS NULL AND claim_expires_at IS NULL)
    ),
    CHECK (expected_fingerprint IS NULL OR length(expected_fingerprint) BETWEEN 1 AND 512),
    CHECK (before_fingerprint IS NULL OR length(before_fingerprint) BETWEEN 1 AND 512),
    CHECK (after_fingerprint IS NULL OR length(after_fingerprint) BETWEEN 1 AND 512),
    CHECK (last_error IS NULL OR length(last_error) BETWEEN 1 AND 2000),
    FOREIGN KEY (operation_id) REFERENCES destructive_operations(operation_id),
    FOREIGN KEY (instance_id, definition_id)
        REFERENCES lore_instances(instance_id, definition_id)
);

CREATE UNIQUE INDEX uq_destructive_target_active_instance
    ON destructive_targets(instance_id)
    WHERE state NOT IN ('COMPLETED', 'ABORTED');

CREATE INDEX idx_destructive_targets_claimable
    ON destructive_targets(state, updated_at, operation_id, instance_id);

CREATE INDEX idx_destructive_targets_operation_state
    ON destructive_targets(operation_id, state, updated_at, instance_id);

CREATE INDEX idx_destructive_targets_expired_claim
    ON destructive_targets(state, claim_expires_at, operation_id, instance_id);

CREATE INDEX idx_destructive_targets_instance_history
    ON destructive_targets(instance_id, created_at DESC, operation_id);

CREATE TRIGGER destructive_operation_identity_is_immutable
BEFORE UPDATE OF operation_id, operation_type, definition_id, exact_instance_id,
    expected_revision, actor_id, idempotency_key, confirmation_token, accepted_at
ON destructive_operations
BEGIN
    SELECT RAISE(ABORT, 'destructive operation identity is immutable');
END;

CREATE TRIGGER destructive_target_identity_is_immutable
BEFORE UPDATE OF operation_id, instance_id, definition_id, expected_applied_revision,
    expected_location_type, expected_location_key, expected_container_path, created_at
ON destructive_targets
BEGIN
    SELECT RAISE(ABORT, 'destructive target snapshot identity is immutable');
END;