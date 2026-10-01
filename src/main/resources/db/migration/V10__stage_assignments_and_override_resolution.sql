-- Two optional reviewer slots per entry: assigned_to holds the Lexical Editor, assigned_chief the Chief Lexical Editor.
ALTER TABLE submission ADD COLUMN assigned_chief VARCHAR(64) REFERENCES app_user(id);

UPDATE submission s
SET assigned_chief = s.assigned_to,
    assigned_to    = NULL
FROM app_user u
WHERE u.id = s.assigned_to
  AND u.role = 'CHIEF_LEXICAL_EDITOR';

CREATE INDEX idx_submission_assigned_chief
    ON submission (assigned_chief, state, updated_at DESC);

-- Why a case was closed outside panel voting (e.g. an emergency override moved the entry out of arbitration).
ALTER TABLE arbitration_case ADD COLUMN resolution_note TEXT;
