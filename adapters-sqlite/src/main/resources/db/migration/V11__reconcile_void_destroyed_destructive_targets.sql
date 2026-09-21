CREATE TEMP TABLE v11_void_destructive_operations (
    operation_id TEXT PRIMARY KEY,
    reconciled_at INTEGER NOT NULL
);

INSERT INTO v11_void_destructive_operations(operation_id, reconciled_at)
SELECT
    target.operation_id,
    MAX(CASE
        WHEN instance.terminal_at > target.updated_at THEN instance.terminal_at
        ELSE target.updated_at
    END)
FROM destructive_targets target
JOIN destructive_operations operation
    ON operation.operation_id = target.operation_id
JOIN lore_instances instance
    ON instance.instance_id = target.instance_id
    AND instance.definition_id = target.definition_id
WHERE target.state = 'PENDING'
  AND operation.state IN ('ACTIVE', 'PAUSED')
  AND instance.lifecycle_state = 'VOID_DESTROYED'
GROUP BY target.operation_id;

INSERT INTO audit_events(
    aggregate_type,
    aggregate_id,
    event_type,
    actor_type,
    actor_id,
    detail_json,
    occurred_at
)
SELECT
    'destructive_operation',
    target.operation_id,
    'destructive_target_satisfied_by_void_loss_migration',
    'system',
    'SYSTEM',
    '{"instanceId":"' || target.instance_id
        || '","effectState":"REMOVED_OBSERVED","source":"V11_LEGACY_VOID"}',
    CASE
        WHEN instance.terminal_at > target.updated_at THEN instance.terminal_at
        ELSE target.updated_at
    END
FROM destructive_targets target
JOIN destructive_operations operation
    ON operation.operation_id = target.operation_id
JOIN lore_instances instance
    ON instance.instance_id = target.instance_id
    AND instance.definition_id = target.definition_id
WHERE target.state = 'PENDING'
  AND operation.state IN ('ACTIVE', 'PAUSED')
  AND instance.lifecycle_state = 'VOID_DESTROYED';

UPDATE destructive_targets AS target
SET state = 'COMPLETED',
    effect_state = 'REMOVED_OBSERVED',
    claim_token = NULL,
    claim_expires_at = NULL,
    before_fingerprint = NULL,
    after_fingerprint = NULL,
    last_error = NULL,
    updated_at = MAX(
        target.updated_at,
        (SELECT instance.terminal_at
         FROM lore_instances instance
         WHERE instance.instance_id = target.instance_id
           AND instance.definition_id = target.definition_id)
    )
WHERE target.state = 'PENDING'
  AND target.operation_id IN (
      SELECT operation_id FROM v11_void_destructive_operations
  )
  AND EXISTS (
      SELECT 1
      FROM lore_instances instance
      WHERE instance.instance_id = target.instance_id
        AND instance.definition_id = target.definition_id
        AND instance.lifecycle_state = 'VOID_DESTROYED'
  );

UPDATE destructive_operations AS operation
SET state = CASE
        WHEN EXISTS (
            SELECT 1 FROM destructive_targets target
            WHERE target.operation_id = operation.operation_id
              AND target.state = 'ABORTED'
        ) THEN 'ABORTED'
        ELSE 'COMPLETED'
    END,
    updated_at = MAX(
        operation.updated_at,
        (SELECT reconciled_at
         FROM v11_void_destructive_operations affected
         WHERE affected.operation_id = operation.operation_id)
    ),
    terminal_at = MAX(
        operation.updated_at,
        (SELECT reconciled_at
         FROM v11_void_destructive_operations affected
         WHERE affected.operation_id = operation.operation_id)
    )
WHERE operation.operation_id IN (
    SELECT operation_id FROM v11_void_destructive_operations
)
  AND operation.state IN ('ACTIVE', 'PAUSED')
  AND NOT EXISTS (
      SELECT 1 FROM destructive_targets target
      WHERE target.operation_id = operation.operation_id
        AND target.state NOT IN ('COMPLETED', 'ABORTED')
  );

DROP TABLE v11_void_destructive_operations;
