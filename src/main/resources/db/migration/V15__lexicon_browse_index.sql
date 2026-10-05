-- Dictionary browse: first Bengali code point of the lemma (skipping punctuation such as a leading
-- parenthesis), indexed with the lemma in code-point order so a letter page is a single index range scan.
ALTER TABLE lexical_entry
    ADD COLUMN initial_letter TEXT GENERATED ALWAYS AS (
        coalesce(substring(lkc_form_key(written_form) from '[\u0980-\u09FF]'), '#')
    ) STORED;

CREATE INDEX idx_lexical_entry_initial ON lexical_entry (initial_letter, written_form COLLATE "C", id);

-- Infix word search ("contains") and suggestions over the normalised form.
CREATE INDEX idx_lexical_entry_form_key_trgm ON lexical_entry USING gin (form_key gin_trgm_ops);
