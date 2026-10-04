-- Export scopes: ALL, NEW (platform entries validated since the previous ALL/NEW export),
-- XML (original lexicon only), RANGE (platform entries validated in a date range).
ALTER TABLE export_record
    ADD COLUMN scope        VARCHAR(10),
    ADD COLUMN version      VARCHAR(40),
    ADD COLUMN pos_filter   VARCHAR(20),
    ADD COLUMN range_from   DATE,
    ADD COLUMN range_to     DATE,
    ADD COLUMN baseline_at  TIMESTAMPTZ,
    ADD COLUMN cutoff_at    TIMESTAMPTZ,
    ADD COLUMN sense_count  INT NOT NULL DEFAULT 0,
    ADD COLUMN user_senses  INT NOT NULL DEFAULT 0,
    ADD COLUMN file_bytes   BIGINT NOT NULL DEFAULT 0;

UPDATE export_record
SET scope = CASE
        WHEN config_json LIKE '%origin=xml%' THEN 'XML'
        WHEN config_json LIKE '%origin=user%' THEN 'USER'
        ELSE 'ALL'
    END,
    cutoff_at = timestamp
WHERE scope IS NULL;

ALTER TABLE export_record
    ALTER COLUMN scope SET NOT NULL,
    ADD CONSTRAINT ck_export_scope CHECK (scope IN ('ALL', 'NEW', 'XML', 'RANGE', 'USER'));

CREATE INDEX idx_export_record_ts ON export_record (timestamp DESC);
CREATE INDEX idx_export_record_baseline ON export_record (cutoff_at DESC) WHERE scope IN ('ALL', 'NEW');
CREATE INDEX idx_sense_promoted_at ON sense (promoted_at) WHERE origin = 'USER';
