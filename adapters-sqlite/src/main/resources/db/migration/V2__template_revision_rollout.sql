CREATE INDEX idx_instances_revision_rollout
    ON lore_instances(definition_id, lifecycle_state, desired_revision, instance_id);

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
