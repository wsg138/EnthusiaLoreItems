CREATE TABLE template_edit_confirmations (
    confirmation_id TEXT PRIMARY KEY,
    definition_id TEXT NOT NULL,
    expected_revision INTEGER NOT NULL CHECK (expected_revision >= 1),
    target_revision INTEGER NOT NULL CHECK (target_revision = expected_revision + 1),
    actor_id TEXT NOT NULL,
    created_at INTEGER NOT NULL CHECK (created_at >= 0),
    FOREIGN KEY (definition_id, target_revision)
        REFERENCES lore_definition_revisions(definition_id, revision)
);

CREATE INDEX idx_template_edit_confirmation_definition
    ON template_edit_confirmations(definition_id, target_revision, created_at);

CREATE INDEX idx_anomalies_definition_status
    ON instance_anomalies(definition_id, status, anomaly_id);
