-- V7: Indexes for growing workflow tables, idempotent submission creation,
--     and optimistic locking on submission status changes.

-- Submission lists / queues are ordered by most recently updated
CREATE INDEX IF NOT EXISTS idx_submission_updated
    ON submission (updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_submission_state_updated
    ON submission (state, updated_at DESC);

-- Daily entry limit: count(contributor, created_at > now - 24h)
CREATE INDEX IF NOT EXISTS idx_submission_contributor_created
    ON submission (contributor_id, created_at DESC);

-- Open-submission duplicate checks skip REJECTED rows
DROP INDEX IF EXISTS idx_submission_lemma_pos;
CREATE INDEX IF NOT EXISTS idx_submission_lemma_pos_active
    ON submission (written_form, part_of_speech)
    WHERE state <> 'REJECTED';

-- History per submission, already in display order
DROP INDEX IF EXISTS idx_transition_submission;
CREATE INDEX IF NOT EXISTS idx_transition_submission_created
    ON state_transition (submission_id, created_at);

-- Reverse relation lookups (what points at this synset / sense)
CREATE INDEX IF NOT EXISTS idx_synset_rel_target
    ON synset_relation (target_synset_id);
CREATE INDEX IF NOT EXISTS idx_sense_rel_target
    ON sense_relation (sense_id_2);

-- Sense lookup by (synset, entry) for member-word batching
CREATE INDEX IF NOT EXISTS idx_sense_synset_entry
    ON sense (synset_id, lexical_entry_id);

-- Optimistic locking: concurrent status changes on the same entry fail fast (409)
ALTER TABLE submission ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

-- Idempotency keys: a retried / double-clicked POST returns the first result instead of a second row
CREATE TABLE IF NOT EXISTS idempotency_key (
    id             BIGSERIAL    PRIMARY KEY,
    user_id        VARCHAR(64)  NOT NULL,
    idem_key       VARCHAR(100) NOT NULL,
    endpoint       VARCHAR(120) NOT NULL,
    request_hash   VARCHAR(64)  NOT NULL,
    status_code    INT,
    response_json  TEXT,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    completed_at   TIMESTAMPTZ,
    CONSTRAINT uq_idempotency_user_key UNIQUE (user_id, endpoint, idem_key)
);
CREATE INDEX IF NOT EXISTS idx_idempotency_created
    ON idempotency_key (created_at);
