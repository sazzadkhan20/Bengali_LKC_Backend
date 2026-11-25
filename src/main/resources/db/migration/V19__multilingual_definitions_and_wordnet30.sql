-- V19: multilingual synset definitions + Princeton WordNet 3.0 ILI registry.
--
-- 1. A synset has zero or more definitions, at most one per language (ben, eng).
--    synset.definition_text / definition_language are kept as the synset's primary gloss
--    (Bengali first, else English), maintained by a trigger, for short previews and rollback safety.
-- 2. A submission carries its proposed definitions as a JSON list in definitions_json.
--    The legacy single-definition columns stay, written with the primary definition.
-- 3. wordnet_ili becomes the Princeton WordNet 3.0 registry (loaded at startup by
--    WordnetRegistryLoader from the backend resources) with a ranked lemma index.

-- 1. Synset definitions ------------------------------------------------------------------------

CREATE TABLE synset_definition (
    synset_id            VARCHAR(100) NOT NULL REFERENCES synset (id) ON DELETE CASCADE,
    language             VARCHAR(5)   NOT NULL CHECK (language IN ('ben', 'eng')),
    text                 TEXT         NOT NULL CHECK (btrim(text) <> ''),
    source_submission_id VARCHAR(64),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    PRIMARY KEY (synset_id, language)
);
CREATE INDEX idx_synset_definition_text_trgm ON synset_definition USING gin (lower(text) gin_trgm_ops);
CREATE INDEX idx_synset_definition_source_sub ON synset_definition (source_submission_id)
    WHERE source_submission_id IS NOT NULL;

INSERT INTO synset_definition (synset_id, language, text, created_at)
SELECT id,
       CASE WHEN lower(btrim(coalesce(definition_language, ''))) = 'eng' THEN 'eng' ELSE 'ben' END,
       btrim(definition_text),
       created_at
FROM synset
WHERE definition_text IS NOT NULL AND btrim(definition_text) <> '';

CREATE OR REPLACE FUNCTION lkc_sync_synset_primary_definition() RETURNS trigger
    LANGUAGE plpgsql AS
$$
DECLARE
    sid VARCHAR(100);
BEGIN
    FOR sid IN
        SELECT DISTINCT x FROM unnest(CASE TG_OP
            WHEN 'INSERT' THEN ARRAY [NEW.synset_id]
            WHEN 'DELETE' THEN ARRAY [OLD.synset_id]
            ELSE ARRAY [OLD.synset_id, NEW.synset_id] END) AS x
    LOOP
        UPDATE synset s
        SET definition_text = p.text, definition_language = p.language
        FROM (SELECT sid AS synset_id) k
        LEFT JOIN LATERAL (
            SELECT d.text, d.language FROM synset_definition d
            WHERE d.synset_id = k.synset_id
            ORDER BY CASE d.language WHEN 'ben' THEN 0 ELSE 1 END
            LIMIT 1
        ) p ON TRUE
        WHERE s.id = k.synset_id
          AND (s.definition_text IS DISTINCT FROM p.text OR s.definition_language IS DISTINCT FROM p.language);
    END LOOP;
    RETURN NULL;
END
$$;

CREATE TRIGGER trg_synset_definition_primary
    AFTER INSERT OR UPDATE OR DELETE ON synset_definition
    FOR EACH ROW EXECUTE FUNCTION lkc_sync_synset_primary_definition();

-- 2. Submission definitions --------------------------------------------------------------------

ALTER TABLE submission ADD COLUMN definitions_json TEXT;

UPDATE submission
SET definitions_json = json_build_array(json_build_object(
        'language', CASE WHEN lower(btrim(coalesce(definition_language, ''))) = 'eng' THEN 'eng' ELSE 'ben' END,
        'text', btrim(definition_text)))::text
WHERE definition_text IS NOT NULL AND btrim(definition_text) <> '';

-- All definition texts of a submission, one per line (search helper).
CREATE OR REPLACE FUNCTION lkc_definitions_text(defs TEXT) RETURNS TEXT
    LANGUAGE sql IMMUTABLE PARALLEL SAFE AS
$$
SELECT coalesce(string_agg(t.d ->> 'text', E'\n' ORDER BY t.ord), '')
FROM jsonb_array_elements(
        CASE WHEN defs IS NULL OR btrim(defs) = '' THEN '[]'::jsonb ELSE defs::jsonb END
     ) WITH ORDINALITY AS t(d, ord)
$$;

-- 3. Princeton WordNet 3.0 registry ------------------------------------------------------------

-- The previous rows were derived from the imported synsets (no English words); the loader
-- replaces them with the full WordNet 3.0 registry on the next start.
DELETE FROM wordnet_ili;

CREATE TABLE wordnet_lemma (
    lemma      TEXT        NOT NULL,
    pos        VARCHAR(5)  NOT NULL,
    ili        VARCHAR(50) NOT NULL REFERENCES wordnet_ili (ili) ON DELETE CASCADE,
    sense_rank INT         NOT NULL,
    PRIMARY KEY (lemma, ili)
);
CREATE INDEX idx_wordnet_lemma_prefix ON wordnet_lemma (lemma text_pattern_ops);
CREATE INDEX idx_wordnet_lemma_trgm ON wordnet_lemma USING gin (lemma gin_trgm_ops);
CREATE INDEX idx_wordnet_lemma_ili ON wordnet_lemma (ili);

CREATE TABLE wordnet_registry (
    id              SMALLINT    PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    version         VARCHAR(20) NOT NULL,
    synset_count    INT         NOT NULL,
    lemma_row_count INT         NOT NULL,
    lemma_count     INT         NOT NULL,
    loaded_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
