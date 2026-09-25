ALTER TABLE instance_current_state RENAME TO instance_current_state_v9;

ALTER TABLE instance_observations RENAME TO instance_observations_v9;

CREATE TABLE instance_observations (
    observation_id INTEGER PRIMARY KEY AUTOINCREMENT,
    instance_id TEXT NOT NULL,
    definition_id TEXT NOT NULL,
    location_type TEXT NOT NULL CHECK (
        location_type IN (
            'PLAYER_INVENTORY',
            'PLAYER_ENDER_CHEST',
            'BLOCK_CONTAINER',
            'NESTED_CONTAINER',
            'DROPPED_ITEM',
            'ITEM_FRAME',
            'ITEM_DISPLAY',
            'ARMOR_STAND',
            'QUEUED_DELIVERY',
            'PENDING_MUTATION',
            'VOID_DESTROYED',
            'DUPLICATE_CONFLICT'
        )
    ),
    location_key TEXT NOT NULL,
    container_path TEXT,
    confidence TEXT NOT NULL CHECK (
        confidence IN ('CONFIRMED_NOW', 'LAST_CONFIRMED', 'CONFLICTING', 'TERMINAL_VOID')
    ),
    source TEXT NOT NULL,
    observed_at INTEGER NOT NULL,
    UNIQUE (observation_id, instance_id),
    CHECK (
        (confidence = 'TERMINAL_VOID' AND location_type = 'VOID_DESTROYED')
        OR (confidence <> 'TERMINAL_VOID' AND location_type <> 'VOID_DESTROYED')
    ),
    FOREIGN KEY (instance_id, definition_id)
        REFERENCES lore_instances(instance_id, definition_id)
);

INSERT INTO instance_observations(
    observation_id,
    instance_id,
    definition_id,
    location_type,
    location_key,
    container_path,
    confidence,
    source,
    observed_at
)
SELECT
    observation_id,
    instance_id,
    definition_id,
    location_type,
    location_key,
    container_path,
    confidence,
    source,
    observed_at
FROM instance_observations_v9;

CREATE TABLE instance_current_state (
    instance_id TEXT PRIMARY KEY,
    state TEXT NOT NULL CHECK (
        state IN (
            'CONFIRMED_NOW',
            'LAST_CONFIRMED',
            'CONFLICTING',
            'TERMINAL_VOID',
            'MISSING_UNRESOLVED'
        )
    ),
    location_type TEXT,
    location_key TEXT,
    container_path TEXT,
    last_observation_id INTEGER,
    state_revision INTEGER NOT NULL DEFAULT 0 CHECK (state_revision >= 0),
    updated_at INTEGER NOT NULL,
    CHECK (
        (
            state = 'MISSING_UNRESOLVED'
            AND location_type IS NULL
            AND location_key IS NULL
            AND container_path IS NULL
            AND last_observation_id IS NULL
        )
        OR (
            state <> 'MISSING_UNRESOLVED'
            AND location_type IS NOT NULL
            AND location_key IS NOT NULL
            AND last_observation_id IS NOT NULL
        )
    ),
    CHECK (
        (state = 'TERMINAL_VOID' AND location_type = 'VOID_DESTROYED')
        OR (state <> 'TERMINAL_VOID' AND location_type <> 'VOID_DESTROYED')
    ),
    FOREIGN KEY (instance_id) REFERENCES lore_instances(instance_id),
    FOREIGN KEY (last_observation_id, instance_id)
        REFERENCES instance_observations(observation_id, instance_id)
);

INSERT INTO instance_current_state(
    instance_id,
    state,
    location_type,
    location_key,
    container_path,
    last_observation_id,
    state_revision,
    updated_at
)
SELECT
    instance_id,
    state,
    location_type,
    location_key,
    container_path,
    last_observation_id,
    state_revision,
    updated_at
FROM instance_current_state_v9;

DROP TABLE instance_current_state_v9;
DROP TABLE instance_observations_v9;

CREATE INDEX idx_observations_instance_time
    ON instance_observations(instance_id, observed_at DESC, observation_id DESC);

CREATE INDEX idx_observations_location
    ON instance_observations(location_type, location_key, observed_at DESC);

CREATE TRIGGER canonicalize_player_inventory_observation_insert
AFTER INSERT ON instance_observations
WHEN NEW.location_type = 'PLAYER_INVENTORY'
  AND NEW.location_key NOT LIKE 'player:%'
BEGIN
    UPDATE instance_observations
    SET location_key = 'player:' || NEW.location_key
    WHERE observation_id = NEW.observation_id;
END;

CREATE TRIGGER canonicalize_player_inventory_current_insert
AFTER INSERT ON instance_current_state
WHEN NEW.location_type = 'PLAYER_INVENTORY'
  AND NEW.location_key NOT LIKE 'player:%'
BEGIN
    UPDATE instance_current_state
    SET location_key = 'player:' || NEW.location_key
    WHERE instance_id = NEW.instance_id;
END;

CREATE TRIGGER canonicalize_player_inventory_current_update
AFTER UPDATE OF location_type, location_key ON instance_current_state
WHEN NEW.location_type = 'PLAYER_INVENTORY'
  AND NEW.location_key NOT LIKE 'player:%'
BEGIN
    UPDATE instance_current_state
    SET location_key = 'player:' || NEW.location_key
    WHERE instance_id = NEW.instance_id;
END;
