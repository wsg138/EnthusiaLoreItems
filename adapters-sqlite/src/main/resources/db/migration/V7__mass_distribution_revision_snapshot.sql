CREATE TABLE distribution_campaign_revision_snapshots (
    campaign_id TEXT PRIMARY KEY,
    definition_id TEXT NOT NULL,
    definition_revision INTEGER NOT NULL CHECK (definition_revision >= 1),
    created_at INTEGER NOT NULL CHECK (created_at >= 0),
    FOREIGN KEY (campaign_id) REFERENCES distribution_campaigns(campaign_id),
    FOREIGN KEY (definition_id, definition_revision)
        REFERENCES lore_definition_revisions(definition_id, revision)
);

INSERT INTO distribution_campaign_revision_snapshots(
    campaign_id,
    definition_id,
    definition_revision,
    created_at
)
SELECT
    campaign.campaign_id,
    campaign.definition_id,
    definition.current_revision,
    campaign.created_at
FROM distribution_campaigns campaign
JOIN lore_definitions definition
    ON definition.definition_id = campaign.definition_id;

CREATE INDEX idx_distribution_campaign_revision
    ON distribution_campaign_revision_snapshots(definition_id, definition_revision, campaign_id);

CREATE TRIGGER distribution_campaign_revision_is_immutable
BEFORE UPDATE ON distribution_campaign_revision_snapshots
BEGIN
    SELECT RAISE(ABORT, 'distribution campaign revision snapshot is immutable');
END;

CREATE TRIGGER distribution_campaign_revision_cannot_be_deleted
BEFORE DELETE ON distribution_campaign_revision_snapshots
BEGIN
    SELECT RAISE(ABORT, 'distribution campaign revision snapshot cannot be deleted');
END;
