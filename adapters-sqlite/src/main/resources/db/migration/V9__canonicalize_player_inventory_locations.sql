UPDATE instance_current_state
SET location_key = 'player:' || location_key
WHERE location_type = 'PLAYER_INVENTORY'
  AND location_key NOT LIKE 'player:%';

UPDATE instance_observations
SET location_key = 'player:' || location_key
WHERE location_type = 'PLAYER_INVENTORY'
  AND location_key NOT LIKE 'player:%';

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
