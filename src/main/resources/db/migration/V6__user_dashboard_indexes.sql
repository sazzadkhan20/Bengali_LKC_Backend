-- V6: Per-user dashboard queries (who submitted / reviewed / validated what)
CREATE INDEX IF NOT EXISTS idx_transition_actor
    ON state_transition (actor_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_audit_target
    ON audit_event (target_type, target_id);

CREATE INDEX IF NOT EXISTS idx_submission_contributor_state
    ON submission (contributor_id, state);

CREATE INDEX IF NOT EXISTS idx_submission_validated_by
    ON submission (validated_by);
