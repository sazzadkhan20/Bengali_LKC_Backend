-- Queue / My Submissions browse API: filters (state, assignee, contributor),
-- free-text search on word or definition, ordered by last update.

-- Trigram search on the contributor's word and own definition (pg_trgm enabled in V5).
CREATE INDEX IF NOT EXISTS idx_submission_written_form_trgm
    ON submission USING gin (lower(written_form) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_submission_definition_trgm
    ON submission USING gin (lower(definition_text) gin_trgm_ops);

-- "Assigned to me" inside a state queue, newest first.
CREATE INDEX IF NOT EXISTS idx_submission_assigned_state_updated
    ON submission (assigned_to, state, updated_at DESC);

-- My Submissions: contributor (+ optional state), newest first.
DROP INDEX IF EXISTS idx_submission_contributor_state;
CREATE INDEX IF NOT EXISTS idx_submission_contributor_state_updated
    ON submission (contributor_id, state, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_submission_contributor_updated
    ON submission (contributor_id, updated_at DESC);
-- Prefix of the composite indexes above.
DROP INDEX IF EXISTS idx_submission_contributor;

-- Who made the most recent change (create, transition, assignment).
ALTER TABLE submission ADD COLUMN IF NOT EXISTS last_updated_by VARCHAR(64);

UPDATE submission s
SET last_updated_by = t.actor_id
FROM (
    SELECT DISTINCT ON (submission_id) submission_id, actor_id
    FROM state_transition
    ORDER BY submission_id, created_at DESC
) t
WHERE t.submission_id = s.id AND s.last_updated_by IS NULL;

UPDATE submission SET last_updated_by = contributor_id WHERE last_updated_by IS NULL;
