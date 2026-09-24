-- V4: Fast Quick Input lookups against the full UKC corpus
CREATE INDEX IF NOT EXISTS idx_lexical_entry_lemma_pos
    ON lexical_entry (written_form, part_of_speech);

CREATE INDEX IF NOT EXISTS idx_lexical_entry_lemma_lower
    ON lexical_entry (lower(written_form) text_pattern_ops);
