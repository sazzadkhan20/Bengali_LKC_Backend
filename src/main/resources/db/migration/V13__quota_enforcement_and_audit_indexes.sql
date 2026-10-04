-- Submission attempts rejected by the session or daily limit. Together with accepted submissions
-- they form the rolling 24-hour attempt count that triggers automatic suspension.
CREATE TABLE quota_block (
    id         BIGSERIAL PRIMARY KEY,
    user_id    VARCHAR(64) NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    limit_kind VARCHAR(20) NOT NULL CHECK (limit_kind IN ('SESSION', 'DAY')),
    blocked_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_quota_block_user_time ON quota_block (user_id, blocked_at DESC);

-- Rolling-window counts per contributor (quota, anomaly) and audit-log filtering.
CREATE INDEX IF NOT EXISTS idx_submission_contributor_created ON submission (contributor_id, created_at DESC);
CREATE INDEX idx_audit_type_ts ON audit_event (target_type, "timestamp" DESC);
CREATE INDEX idx_audit_action_ts ON audit_event (action, "timestamp" DESC);
CREATE INDEX idx_audit_actor_ts ON audit_event (actor_id, "timestamp" DESC);
DROP INDEX IF EXISTS idx_audit_actor;

CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX idx_audit_detail_trgm ON audit_event USING gin (detail gin_trgm_ops);
