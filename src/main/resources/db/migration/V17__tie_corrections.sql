-- Structured record of what the System Moderator changed when resolving a tied arbitration case.
ALTER TABLE arbitration_case ADD COLUMN tie_corrections_json TEXT;

-- Earlier tie-breaks only kept the changed field names inside resolution_note.
UPDATE arbitration_case
SET tie_corrections_json = CASE
        WHEN resolution_note ~ 'corrected the entry \(' THEN (
            SELECT coalesce(jsonb_agg(jsonb_build_object('field', trim(f), 'before', NULL, 'after', NULL)), '[]'::jsonb)::text
            FROM unnest(string_to_array(substring(resolution_note FROM 'corrected the entry \(([^)]*)\)'), ',')) AS f
        )
        ELSE '[]'
    END
WHERE verdict_by = 'TIE_BREAK' AND tie_corrections_json IS NULL;

CREATE INDEX idx_arbitration_case_tie_break ON arbitration_case (resolved_at DESC) WHERE verdict_by = 'TIE_BREAK';
