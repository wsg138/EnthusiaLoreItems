UPDATE instance_current_state
SET location_key = 'player:' || location_key,
    container_path = 'slot:' || substr(container_path, 8)
WHERE location_type = 'PLAYER_INVENTORY'
  AND location_key NOT LIKE 'player:%'
  AND container_path GLOB 'hotbar:[0-9]*'
  AND EXISTS (
      SELECT 1
      FROM instance_observations AS observation
      WHERE observation.observation_id =
              instance_current_state.last_observation_id
        AND observation.source = 'held-item-adoption'
  );

UPDATE instance_observations
SET location_key = 'player:' || location_key,
    container_path = 'slot:' || substr(container_path, 8)
WHERE source = 'held-item-adoption'
  AND location_type = 'PLAYER_INVENTORY'
  AND location_key NOT LIKE 'player:%'
  AND container_path GLOB 'hotbar:[0-9]*';
