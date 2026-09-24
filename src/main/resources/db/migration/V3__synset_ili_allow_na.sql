-- V3: Allow multiple synsets without a Princeton ILI (XML uses ili="n/a").
-- Real ILI codes remain unique; n/a may repeat.

ALTER TABLE synset DROP CONSTRAINT IF EXISTS uq_synset_ili;

DROP INDEX IF EXISTS idx_synset_ili;

CREATE INDEX idx_synset_ili ON synset (ili);

CREATE UNIQUE INDEX uq_synset_ili_real
    ON synset (ili)
    WHERE lower(ili) <> 'n/a';
