CREATE TABLE pending_mutations_v3 (
    mutation_id TEXT PRIMARY KEY,
    mutation_type TEXT NOT NULL,
    definition_id TEXT,
    instance_id TEXT,
    desired_revision INTEGER,
    state TEXT NOT NULL CHECK (
        state IN (
            'PENDING',
            'CLAIMED',
            'APPLIED',
            'VERIFIED',
            'COMPLETED',
            'REVIEW_REQUIRED',
            'CANCELLED'
        )
    ),
    claim_token TEXT,
    claim_expires_at INTEGER,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at INTEGER,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (definition_id) REFERENCES lore_definitions(definition_id),
    FOREIGN KEY (instance_id) REFERENCES lore_instances(instance_id)
);

INSERT INTO pending_mutations_v3(
    mutation_id,
    mutation_type,
    definition_id,
    instance_id,
    desired_revision,
    state,
    claim_token,
    claim_expires_at,
    attempt_count,
    next_attempt_at,
    created_at,
    updated_at
)
SELECT
    mutation_id,
    mutation_type,
    definition_id,
    instance_id,
    desired_revision,
    state,
    claim_token,
    claim_expires_at,
    attempt_count,
    next_attempt_at,
    created_at,
    updated_at
FROM pending_mutations;

DROP TABLE pending_mutations;
ALTER TABLE pending_mutations_v3 RENAME TO pending_mutations;

CREATE INDEX idx_mutations_claimable
    ON pending_mutations(state, next_attempt_at, created_at);

CREATE UNIQUE INDEX uq_template_update_instance_revision
    ON pending_mutations(instance_id, desired_revision)
    WHERE mutation_type = 'TEMPLATE_UPDATE'
        AND instance_id IS NOT NULL
        AND desired_revision IS NOT NULL;

CREATE INDEX idx_template_update_mutations
    ON pending_mutations(
        definition_id,
        desired_revision,
        state,
        created_at,
        mutation_id
    )
    WHERE mutation_type = 'TEMPLATE_UPDATE';

CREATE INDEX idx_mutations_type_claimable
    ON pending_mutations(
        mutation_type,
        state,
        next_attempt_at,
        created_at,
        mutation_id
    );

CREATE INDEX idx_mutations_type_review
    ON pending_mutations(
        mutation_type,
        state,
        updated_at,
        mutation_id
    );
