-- Arbitration is always contributor vs reviewer (Lexical Editor or Chief Lexical Editor), whoever opened it.
-- Each side keeps one editable note until the daily deadline check closes the 72-hour window; voting then
-- runs in 7-day rounds closed by the same check.
ALTER TABLE arbitration_case
    ADD COLUMN origin              VARCHAR(30),
    ADD COLUMN stage               VARCHAR(20),
    ADD COLUMN contributor_id      VARCHAR(64) REFERENCES app_user(id),
    ADD COLUMN reviewer_id         VARCHAR(64) REFERENCES app_user(id),
    ADD COLUMN contributor_note    TEXT,
    ADD COLUMN contributor_note_at TIMESTAMPTZ,
    ADD COLUMN reviewer_note       TEXT,
    ADD COLUMN reviewer_note_at    TIMESTAMPTZ,
    ADD COLUMN window_closed_at    TIMESTAMPTZ,
    ADD COLUMN voting_opened_at    TIMESTAMPTZ,
    ADD COLUMN voting_deadline     TIMESTAMPTZ,
    ADD COLUMN voting_extensions   INT NOT NULL DEFAULT 0,
    ADD COLUMN resulting_state     VARCHAR(40);

UPDATE arbitration_case
SET stage = CASE WHEN pre_arbitration_state IN ('EDITOR_APPROVED', 'C_FLAGGED') THEN 'CHIEF' ELSE 'EDITOR' END;

UPDATE arbitration_case c
SET origin              = 'CONTRIBUTOR_DISPUTE',
    contributor_id      = c.claimant_id,
    reviewer_id         = c.accused_id,
    contributor_note    = c.dispute_note,
    contributor_note_at = c.opened_at,
    reviewer_note       = c.counter_claim,
    reviewer_note_at    = CASE WHEN c.counter_claim IS NOT NULL THEN c.updated_at END
WHERE c.dispute_type IN ('DISPUTE_FLAG', 'DISPUTE_REJECTION');

UPDATE arbitration_case c
SET origin              = 'REVIEWER_ESCALATION',
    reviewer_id         = c.claimant_id,
    contributor_id      = c.accused_id,
    reviewer_note       = c.dispute_note,
    reviewer_note_at    = c.opened_at,
    contributor_note    = c.counter_claim,
    contributor_note_at = CASE WHEN c.counter_claim IS NOT NULL THEN c.updated_at END
FROM app_user u
WHERE u.id = c.claimant_id
  AND c.origin IS NULL
  AND u.role IN ('LEXICAL_EDITOR', 'CHIEF_LEXICAL_EDITOR');

UPDATE arbitration_case c
SET origin              = 'EMERGENCY_OVERRIDE',
    contributor_id      = s.contributor_id,
    contributor_note    = c.counter_claim,
    contributor_note_at = CASE WHEN c.counter_claim IS NOT NULL THEN c.updated_at END,
    reviewer_id         = (SELECT t.actor_id
                           FROM state_transition t
                           JOIN app_user r ON r.id = t.actor_id
                           WHERE t.submission_id = c.submission_id
                             AND t.to_state = c.pre_arbitration_state
                             AND t.created_at <= c.opened_at
                             AND r.role IN ('LEXICAL_EDITOR', 'CHIEF_LEXICAL_EDITOR')
                           ORDER BY t.created_at DESC
                           LIMIT 1)
FROM submission s
WHERE s.id = c.submission_id
  AND c.origin IS NULL;

UPDATE arbitration_case
SET window_closed_at = updated_at,
    voting_opened_at = updated_at,
    voting_deadline  = updated_at + INTERVAL '7 days'
WHERE status IN ('ACTIVE', 'TIE_PENDING');

ALTER TABLE arbitration_case
    ALTER COLUMN origin SET NOT NULL,
    ALTER COLUMN stage SET NOT NULL,
    ALTER COLUMN contributor_id SET NOT NULL;

CREATE INDEX idx_arb_window_due ON arbitration_case (status, response_deadline);
CREATE INDEX idx_arb_voting_due ON arbitration_case (status, voting_deadline);
CREATE INDEX idx_arb_contributor ON arbitration_case (contributor_id, opened_at DESC);
CREATE INDEX idx_arb_reviewer ON arbitration_case (reviewer_id, opened_at DESC);
