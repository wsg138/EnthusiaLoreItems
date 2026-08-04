CREATE INDEX idx_instances_revision_rollout
    ON lore_instances(definition_id, lifecycle_state, desired_revision, instance_id);

DELETE FROM pending_mutations
WHERE mutation_id IN (
    SELECT mutation_id
    FROM (
        SELECT
            mutation_id,
            ROW_NUMBER() OVER (
                PARTITION BY instance_id, desired_revision
                ORDER BY
                    CASE state
                        WHEN 'COMPLETED' THEN 6
                        WHEN 'REVIEW_REQUIRED' THEN 5
                        WHEN 'VERIFIED' THEN 4
                        WHEN 'APPLIED' THEN 3
                        WHEN 'CLAIMED' THEN 2
                        WHEN 'PENDING' THEN 1
                        ELSE 0
                    END DESC,
                    updated_at DESC,
                    attempt_count DESC,
                    created_at ASC,
                    mutation_id ASC
            ) AS duplicate_rank
        FROM pending_mutations
        WHERE mutation_type = 'TEMPLATE_UPDATE'
            AND instance_id IS NOT NULL
            AND desired_revision IS NOT NULL
    ) ranked_template_updates
    WHERE duplicate_rank > 1
);

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
