ALTER TABLE distribution_recipients RENAME TO distribution_recipients_v5;

CREATE TABLE distribution_recipients (
    campaign_id TEXT NOT NULL,
    recipient_key TEXT NOT NULL CHECK (length(recipient_key) BETWEEN 1 AND 320),
    snapshot_index INTEGER NOT NULL CHECK (snapshot_index >= 0),
    original_value TEXT NOT NULL CHECK (length(original_value) BETWEEN 1 AND 256),
    player_id TEXT,
    state TEXT NOT NULL CHECK (
        state IN (
            'UNRESOLVED',
            'QUEUED_OFFLINE',
            'QUEUED_INVENTORY_FULL',
            'RESERVED_IN_FLIGHT',
            'REVIEW_REQUIRED',
            'DELIVERED',
            'CANCELLED'
        )
    ),
    instance_id TEXT,
    claim_token TEXT,
    claim_expires_at INTEGER,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at INTEGER,
    delivered_at INTEGER,
    updated_at INTEGER NOT NULL CHECK (updated_at >= 0),
    PRIMARY KEY (campaign_id, recipient_key),
    UNIQUE (campaign_id, snapshot_index),
    CHECK (
        (state = 'UNRESOLVED' AND player_id IS NULL)
        OR (state IN ('QUEUED_OFFLINE', 'QUEUED_INVENTORY_FULL', 'RESERVED_IN_FLIGHT', 'DELIVERED')
            AND player_id IS NOT NULL)
        OR state IN ('CANCELLED', 'REVIEW_REQUIRED')
    ),
    CHECK (
        (state = 'RESERVED_IN_FLIGHT'
            AND claim_token IS NOT NULL
            AND length(claim_token) BETWEEN 1 AND 200
            AND claim_expires_at IS NOT NULL
            AND claim_expires_at >= updated_at)
        OR (state <> 'RESERVED_IN_FLIGHT' AND claim_token IS NULL AND claim_expires_at IS NULL)
    ),
    CHECK (
        (state = 'DELIVERED' AND instance_id IS NOT NULL AND delivered_at IS NOT NULL)
        OR (state IN ('RESERVED_IN_FLIGHT', 'REVIEW_REQUIRED') AND delivered_at IS NULL)
        OR (state NOT IN ('RESERVED_IN_FLIGHT', 'DELIVERED', 'REVIEW_REQUIRED')
            AND instance_id IS NULL AND delivered_at IS NULL)
    ),
    CHECK (
        (state IN ('QUEUED_OFFLINE', 'QUEUED_INVENTORY_FULL')
            AND (next_attempt_at IS NULL OR next_attempt_at >= 0))
        OR (state NOT IN ('QUEUED_OFFLINE', 'QUEUED_INVENTORY_FULL')
            AND next_attempt_at IS NULL)
    ),
    CHECK (delivered_at IS NULL OR delivered_at <= updated_at),
    FOREIGN KEY (campaign_id) REFERENCES distribution_campaigns(campaign_id),
    FOREIGN KEY (instance_id) REFERENCES lore_instances(instance_id)
);

INSERT INTO distribution_recipients(
    campaign_id,
    recipient_key,
    snapshot_index,
    original_value,
    player_id,
    state,
    instance_id,
    claim_token,
    claim_expires_at,
    attempt_count,
    next_attempt_at,
    delivered_at,
    updated_at
)
SELECT
    campaign_id,
    recipient_key,
    snapshot_index,
    original_value,
    player_id,
    CASE state
        WHEN 'PENDING_NAME' THEN 'UNRESOLVED'
        WHEN 'PENDING_OFFLINE' THEN 'QUEUED_OFFLINE'
        WHEN 'PENDING_SPACE' THEN 'QUEUED_INVENTORY_FULL'
        WHEN 'RESERVED' THEN 'RESERVED_IN_FLIGHT'
        ELSE state
    END,
    instance_id,
    claim_token,
    claim_expires_at,
    attempt_count,
    next_attempt_at,
    delivered_at,
    updated_at
FROM distribution_recipients_v5;

DROP TABLE distribution_recipients_v5;

CREATE UNIQUE INDEX uq_distribution_recipient_player
    ON distribution_recipients(campaign_id, player_id)
    WHERE player_id IS NOT NULL;

CREATE UNIQUE INDEX uq_distribution_recipient_instance
    ON distribution_recipients(instance_id)
    WHERE instance_id IS NOT NULL;

CREATE INDEX idx_recipients_claimable
    ON distribution_recipients(campaign_id, state, next_attempt_at, snapshot_index);

CREATE INDEX idx_recipients_unresolved_name
    ON distribution_recipients(recipient_key, state, updated_at, campaign_id);

CREATE INDEX idx_recipients_expired_claim
    ON distribution_recipients(state, claim_expires_at, campaign_id, snapshot_index);

CREATE TRIGGER distribution_recipient_snapshot_is_immutable
BEFORE UPDATE OF campaign_id, recipient_key, snapshot_index, original_value
ON distribution_recipients
BEGIN
    SELECT RAISE(ABORT, 'distribution recipient snapshot is immutable');
END;

CREATE TRIGGER distribution_recipient_requires_draft_campaign
BEFORE INSERT ON distribution_recipients
WHEN NOT EXISTS (
    SELECT 1 FROM distribution_campaigns campaign
    WHERE campaign.campaign_id = NEW.campaign_id AND campaign.state = 'DRAFT'
)
BEGIN
    SELECT RAISE(ABORT, 'distribution recipient snapshot is sealed');
END;
