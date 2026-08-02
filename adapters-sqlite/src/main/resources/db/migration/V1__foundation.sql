CREATE TABLE lore_definitions (
    definition_id TEXT PRIMARY KEY,
    lookup_key TEXT NOT NULL,
    display_name TEXT NOT NULL,
    current_revision INTEGER NOT NULL CHECK (current_revision >= 1),
    created_at INTEGER NOT NULL,
    deleted_at INTEGER
);

CREATE UNIQUE INDEX uq_active_definition_lookup_key
    ON lore_definitions(lookup_key)
    WHERE deleted_at IS NULL;

CREATE TABLE lore_definition_revisions (
    definition_id TEXT NOT NULL,
    revision INTEGER NOT NULL CHECK (revision >= 1),
    codec_version INTEGER NOT NULL CHECK (codec_version >= 1),
    template_blob BLOB NOT NULL,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (definition_id, revision),
    FOREIGN KEY (definition_id) REFERENCES lore_definitions(definition_id)
);

CREATE TABLE lore_instances (
    instance_id TEXT PRIMARY KEY,
    definition_id TEXT NOT NULL,
    applied_revision INTEGER NOT NULL CHECK (applied_revision >= 1),
    desired_revision INTEGER NOT NULL CHECK (desired_revision >= applied_revision),
    lifecycle_state TEXT NOT NULL CHECK (
        lifecycle_state IN ('ACTIVE', 'VOID_DESTROYED', 'REMOVED')
    ),
    created_at INTEGER NOT NULL,
    terminal_at INTEGER,
    CHECK (
        (lifecycle_state = 'ACTIVE' AND terminal_at IS NULL)
        OR (lifecycle_state IN ('VOID_DESTROYED', 'REMOVED') AND terminal_at IS NOT NULL)
    ),
    FOREIGN KEY (definition_id) REFERENCES lore_definitions(definition_id),
    FOREIGN KEY (definition_id, applied_revision)
        REFERENCES lore_definition_revisions(definition_id, revision),
    FOREIGN KEY (definition_id, desired_revision)
        REFERENCES lore_definition_revisions(definition_id, revision)
);

CREATE INDEX idx_instances_definition_state
    ON lore_instances(definition_id, lifecycle_state);

CREATE TABLE instance_observations (
    observation_id INTEGER PRIMARY KEY AUTOINCREMENT,
    instance_id TEXT NOT NULL,
    definition_id TEXT NOT NULL,
    location_type TEXT NOT NULL,
    location_key TEXT NOT NULL,
    container_path TEXT,
    confidence TEXT NOT NULL CHECK (
        confidence IN ('CONFIRMED_NOW', 'LAST_CONFIRMED', 'CONFLICTING', 'TERMINAL_VOID')
    ),
    source TEXT NOT NULL,
    observed_at INTEGER NOT NULL,
    FOREIGN KEY (instance_id) REFERENCES lore_instances(instance_id),
    FOREIGN KEY (definition_id) REFERENCES lore_definitions(definition_id)
);

CREATE INDEX idx_observations_instance_time
    ON instance_observations(instance_id, observed_at DESC);

CREATE INDEX idx_observations_location
    ON instance_observations(location_type, location_key);

CREATE TABLE instance_current_state (
    instance_id TEXT PRIMARY KEY,
    state TEXT NOT NULL,
    location_type TEXT,
    location_key TEXT,
    last_observation_id INTEGER,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (instance_id) REFERENCES lore_instances(instance_id),
    FOREIGN KEY (last_observation_id) REFERENCES instance_observations(observation_id)
);

CREATE TABLE instance_anomalies (
    anomaly_id TEXT PRIMARY KEY,
    instance_id TEXT,
    definition_id TEXT,
    anomaly_type TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('OPEN', 'RESOLVED')),
    detail TEXT NOT NULL,
    first_seen_at INTEGER NOT NULL,
    last_seen_at INTEGER NOT NULL,
    resolved_at INTEGER,
    FOREIGN KEY (instance_id) REFERENCES lore_instances(instance_id),
    FOREIGN KEY (definition_id) REFERENCES lore_definitions(definition_id)
);

CREATE INDEX idx_anomalies_open
    ON instance_anomalies(status, anomaly_type, last_seen_at);

CREATE TABLE pending_mutations (
    mutation_id TEXT PRIMARY KEY,
    mutation_type TEXT NOT NULL,
    definition_id TEXT,
    instance_id TEXT,
    desired_revision INTEGER,
    state TEXT NOT NULL CHECK (
        state IN ('PENDING', 'CLAIMED', 'APPLIED', 'VERIFIED', 'COMPLETED', 'REVIEW_REQUIRED')
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

CREATE INDEX idx_mutations_claimable
    ON pending_mutations(state, next_attempt_at, created_at);

CREATE TABLE direct_deliveries (
    delivery_id TEXT PRIMARY KEY,
    instance_id TEXT NOT NULL UNIQUE,
    player_id TEXT NOT NULL,
    state TEXT NOT NULL CHECK (
        state IN ('PENDING', 'RESERVED', 'APPLIED', 'VERIFIED', 'COMPLETED', 'REVIEW_REQUIRED', 'CANCELLED')
    ),
    idempotency_key TEXT NOT NULL UNIQUE,
    claim_token TEXT,
    claim_expires_at INTEGER,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at INTEGER,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (instance_id) REFERENCES lore_instances(instance_id)
);

CREATE INDEX idx_deliveries_claimable
    ON direct_deliveries(state, next_attempt_at, created_at);

CREATE TABLE distribution_campaigns (
    campaign_id TEXT PRIMARY KEY,
    source_fingerprint TEXT NOT NULL UNIQUE,
    source_name TEXT NOT NULL,
    display_name TEXT NOT NULL,
    definition_id TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('DRAFT', 'ACTIVE', 'PAUSED', 'COMPLETED', 'CANCELLED')),
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    completed_at INTEGER,
    FOREIGN KEY (definition_id) REFERENCES lore_definitions(definition_id)
);

CREATE TABLE distribution_recipients (
    campaign_id TEXT NOT NULL,
    recipient_key TEXT NOT NULL,
    original_value TEXT NOT NULL,
    player_id TEXT,
    state TEXT NOT NULL CHECK (
        state IN ('PENDING_NAME', 'PENDING_OFFLINE', 'PENDING_SPACE', 'RESERVED', 'DELIVERED', 'CANCELLED', 'REVIEW_REQUIRED')
    ),
    instance_id TEXT,
    claim_token TEXT,
    claim_expires_at INTEGER,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at INTEGER,
    delivered_at INTEGER,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (campaign_id, recipient_key),
    FOREIGN KEY (campaign_id) REFERENCES distribution_campaigns(campaign_id),
    FOREIGN KEY (instance_id) REFERENCES lore_instances(instance_id)
);

CREATE INDEX idx_recipients_claimable
    ON distribution_recipients(campaign_id, state, next_attempt_at);

CREATE INDEX idx_recipients_unresolved_name
    ON distribution_recipients(recipient_key, state);

CREATE TABLE external_delivery_requests (
    external_operation_id TEXT PRIMARY KEY,
    definition_key TEXT NOT NULL,
    player_id TEXT NOT NULL,
    delivery_id TEXT,
    outcome TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (delivery_id) REFERENCES direct_deliveries(delivery_id)
);

CREATE TABLE deleted_definition_markers (
    definition_id TEXT PRIMARY KEY,
    lookup_key TEXT NOT NULL,
    deleted_at INTEGER NOT NULL,
    FOREIGN KEY (definition_id) REFERENCES lore_definitions(definition_id)
);

CREATE TABLE audit_events (
    audit_id INTEGER PRIMARY KEY AUTOINCREMENT,
    aggregate_type TEXT NOT NULL,
    aggregate_id TEXT NOT NULL,
    event_type TEXT NOT NULL,
    actor_type TEXT NOT NULL,
    actor_id TEXT,
    detail_json TEXT NOT NULL,
    occurred_at INTEGER NOT NULL
);

CREATE INDEX idx_audit_aggregate
    ON audit_events(aggregate_type, aggregate_id, audit_id);
