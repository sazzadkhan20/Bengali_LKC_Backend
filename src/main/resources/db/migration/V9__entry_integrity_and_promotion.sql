-- Canonical comparison key for Bengali written forms.
-- NFC (e.g. precomposed য় and য + nukta compare equal), invisible joiners removed, whitespace collapsed.
CREATE OR REPLACE FUNCTION lkc_form_key(t TEXT) RETURNS TEXT
    LANGUAGE sql IMMUTABLE PARALLEL SAFE AS
$$
SELECT btrim(regexp_replace(
        regexp_replace(normalize(coalesce(t, ''), NFC), '[\u200B-\u200D\u2060\uFEFF]', '', 'g'),
        '\s+', ' ', 'g'))
$$;

ALTER TABLE lexical_entry
    ADD COLUMN form_key TEXT GENERATED ALWAYS AS (lkc_form_key(written_form)) STORED;
ALTER TABLE submission
    ADD COLUMN form_key TEXT GENERATED ALWAYS AS (lkc_form_key(written_form)) STORED;

CREATE INDEX idx_lexical_entry_form_key_pos ON lexical_entry (form_key, part_of_speech);
CREATE INDEX idx_lexical_entry_form_key_prefix ON lexical_entry (form_key text_pattern_ops);
CREATE INDEX idx_submission_form_key_pos ON submission (form_key, part_of_speech);
CREATE INDEX idx_submission_ili_lower ON submission (lower(ili_code)) WHERE ili_code IS NOT NULL;

-- Provenance: XML-imported data vs entries promoted from validated submissions.
ALTER TABLE lexical_entry
    ADD COLUMN origin VARCHAR(10) NOT NULL DEFAULT 'XML' CHECK (origin IN ('XML', 'USER')),
    ADD COLUMN source_submission_id VARCHAR(64),
    ADD COLUMN promoted_at TIMESTAMPTZ;
ALTER TABLE synset
    ADD COLUMN origin VARCHAR(10) NOT NULL DEFAULT 'XML' CHECK (origin IN ('XML', 'USER')),
    ADD COLUMN source_submission_id VARCHAR(64),
    ADD COLUMN promoted_at TIMESTAMPTZ;
ALTER TABLE sense
    ADD COLUMN origin VARCHAR(10) NOT NULL DEFAULT 'XML' CHECK (origin IN ('XML', 'USER')),
    ADD COLUMN source_submission_id VARCHAR(64),
    ADD COLUMN promoted_at TIMESTAMPTZ;
ALTER TABLE sense_relation ADD COLUMN source_submission_id VARCHAR(64);
ALTER TABLE synset_relation ADD COLUMN source_submission_id VARCHAR(64);

CREATE INDEX idx_lexical_entry_source_sub ON lexical_entry (source_submission_id) WHERE source_submission_id IS NOT NULL;
CREATE INDEX idx_synset_source_sub ON synset (source_submission_id) WHERE source_submission_id IS NOT NULL;
CREATE INDEX idx_sense_source_sub ON sense (source_submission_id) WHERE source_submission_id IS NOT NULL;
CREATE INDEX idx_sense_relation_source_sub ON sense_relation (source_submission_id) WHERE source_submission_id IS NOT NULL;
CREATE INDEX idx_synset_relation_source_sub ON synset_relation (source_submission_id) WHERE source_submission_id IS NOT NULL;
CREATE INDEX idx_synset_origin ON synset (origin);
CREATE INDEX idx_sense_origin ON sense (origin);

ALTER TABLE submission
    ADD COLUMN promoted_entry_id VARCHAR(100),
    ADD COLUMN promoted_sense_id VARCHAR(100),
    ADD COLUMN promoted_synset_id VARCHAR(100),
    ADD COLUMN promoted_at TIMESTAMPTZ;

-- Lexicon id counters continue after the highest imported ids so promoted ids never collide.
INSERT INTO id_sequence (name, next_value) VALUES ('synset', 1), ('sense', 1), ('word', 1)
ON CONFLICT (name) DO NOTHING;
UPDATE id_sequence SET next_value = GREATEST(next_value,
        coalesce((SELECT max(substring(id FROM '[0-9]+$')::BIGINT) FROM synset WHERE id ~ '^ben-syn[0-9]+$'), 0) + 1)
WHERE name = 'synset';
UPDATE id_sequence SET next_value = GREATEST(next_value,
        coalesce((SELECT max(substring(id FROM '[0-9]+$')::BIGINT) FROM sense WHERE id ~ '^ben-sen[0-9]+$'), 0) + 1)
WHERE name = 'sense';
UPDATE id_sequence SET next_value = GREATEST(next_value,
        coalesce((SELECT max(substring(id FROM '[0-9]+$')::BIGINT) FROM lexical_entry WHERE id ~ '^ben-word-[nvar][0-9]+$'), 0) + 1)
WHERE name = 'word';
