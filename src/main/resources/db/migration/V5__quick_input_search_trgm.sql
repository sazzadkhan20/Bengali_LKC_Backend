-- V5: Extra indexes for Quick Input autocomplete / validation against full UKC corpus
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Synset id prefix search (ben-syn…)
CREATE INDEX IF NOT EXISTS idx_synset_id_prefix
    ON synset (id text_pattern_ops);

-- Definition substring search (used when lemma search is thin)
CREATE INDEX IF NOT EXISTS idx_synset_definition_trgm
    ON synset USING gin (lower(definition_text) gin_trgm_ops);

-- ILI registry word search
CREATE INDEX IF NOT EXISTS idx_wordnet_ili_words_trgm
    ON wordnet_ili USING gin (lower(words_csv) gin_trgm_ops);
