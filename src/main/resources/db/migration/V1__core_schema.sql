-- V1__core_schema.sql
-- Bengali LKC Framework — PostgreSQL schema
-- Architecture: Modular Layered Monolith (UKC lexicon + workflow + identity)

CREATE TABLE lexicon (
    id               VARCHAR(100) PRIMARY KEY,
    label            VARCHAR(100) NOT NULL,
    language         VARCHAR(10)  NOT NULL,
    email            VARCHAR(255),
    license          VARCHAR(255),
    version          VARCHAR(20),
    dc_source        TEXT,
    dc_description   TEXT,
    dc_publisher     VARCHAR(255),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE lexical_entry (
    id               VARCHAR(100) PRIMARY KEY,
    lexicon_id       VARCHAR(100) NOT NULL REFERENCES lexicon(id) ON DELETE CASCADE,
    written_form     TEXT         NOT NULL,
    part_of_speech   VARCHAR(5)   NOT NULL,
    crowdsourced     BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_lexical_entry_lemma ON lexical_entry (written_form);
CREATE INDEX idx_lexical_entry_pos ON lexical_entry (part_of_speech);
CREATE INDEX idx_lexical_entry_lexicon ON lexical_entry (lexicon_id);

CREATE TABLE synset (
    id                   VARCHAR(100) PRIMARY KEY,
    lexicon_id           VARCHAR(100) NOT NULL REFERENCES lexicon(id) ON DELETE CASCADE,
    ili                  VARCHAR(50)  NOT NULL,
    part_of_speech       VARCHAR(5)   NOT NULL,
    lexicalized          BOOLEAN      NOT NULL DEFAULT TRUE,
    definition_language  VARCHAR(5),
    definition_text      TEXT,
    crowdsourced         BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_synset_ili UNIQUE (ili)
);
CREATE INDEX idx_synset_ili ON synset (ili);
CREATE INDEX idx_synset_pos ON synset (part_of_speech);

CREATE TABLE sense (
    id                 VARCHAR(100) PRIMARY KEY,
    lexical_entry_id   VARCHAR(100) NOT NULL REFERENCES lexical_entry(id) ON DELETE CASCADE,
    synset_id          VARCHAR(100) NOT NULL REFERENCES synset(id) ON DELETE RESTRICT,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_sense_entry ON sense (lexical_entry_id);
CREATE INDEX idx_sense_synset ON sense (synset_id);

CREATE TABLE sense_relation_type (
    id              BIGSERIAL PRIMARY KEY,
    relation_name   VARCHAR(100) NOT NULL UNIQUE
);

CREATE TABLE synset_relation_type (
    id              BIGSERIAL PRIMARY KEY,
    relation_name   VARCHAR(100) NOT NULL UNIQUE
);

CREATE TABLE sense_relation (
    id                  BIGSERIAL PRIMARY KEY,
    sense_id_1          VARCHAR(100) NOT NULL REFERENCES sense(id) ON DELETE CASCADE,
    sense_id_2          VARCHAR(100) NOT NULL REFERENCES sense(id) ON DELETE CASCADE,
    relation_type_id    BIGINT       NOT NULL REFERENCES sense_relation_type(id),
    CONSTRAINT uq_sense_rel UNIQUE (sense_id_1, sense_id_2, relation_type_id)
);
CREATE INDEX idx_sense_rel_src ON sense_relation (sense_id_1);

CREATE TABLE synset_relation (
    id                  BIGSERIAL PRIMARY KEY,
    source_synset_id    VARCHAR(100) NOT NULL REFERENCES synset(id) ON DELETE CASCADE,
    target_synset_id    VARCHAR(100) NOT NULL REFERENCES synset(id) ON DELETE CASCADE,
    relation_type_id    BIGINT       NOT NULL REFERENCES synset_relation_type(id),
    CONSTRAINT uq_synset_rel UNIQUE (source_synset_id, target_synset_id, relation_type_id)
);
CREATE INDEX idx_synset_rel_src ON synset_relation (source_synset_id);

-- Identity -----------------------------------------------------------------
CREATE TABLE app_user (
    id              VARCHAR(64)  PRIMARY KEY,
    username        VARCHAR(30)  NOT NULL UNIQUE,
    name            VARCHAR(120) NOT NULL,
    email           VARCHAR(255) NOT NULL UNIQUE,
    role            VARCHAR(40)  NOT NULL,
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    suspended       BOOLEAN      NOT NULL DEFAULT FALSE,
    auto_announce   BOOLEAN      NOT NULL DEFAULT TRUE,
    dob             DATE,
    institution     VARCHAR(255),
    occupation      VARCHAR(255),
    joined_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_user_role ON app_user (role);

CREATE TABLE credential (
    id               VARCHAR(64)  PRIMARY KEY,
    user_id          VARCHAR(64)  NOT NULL UNIQUE REFERENCES app_user(id) ON DELETE CASCADE,
    username         VARCHAR(30)  NOT NULL UNIQUE,
    password_hash    VARCHAR(255) NOT NULL,
    one_time         BOOLEAN      NOT NULL DEFAULT TRUE,
    issued_by        VARCHAR(64),
    issued_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    last_changed_at  TIMESTAMPTZ,
    deactivated      BOOLEAN      NOT NULL DEFAULT FALSE,
    otp_code_hash    VARCHAR(255),
    otp_expires_at   TIMESTAMPTZ,
    failed_attempts  INT          NOT NULL DEFAULT 0,
    locked_until     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE refresh_token (
    id            BIGSERIAL PRIMARY KEY,
    user_id       VARCHAR(64)  NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    token_hash    VARCHAR(128) NOT NULL UNIQUE,
    expires_at    TIMESTAMPTZ  NOT NULL,
    revoked       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_refresh_user ON refresh_token (user_id);

-- Workflow -----------------------------------------------------------------
CREATE TABLE submission (
    id                     VARCHAR(64)  PRIMARY KEY,
    state                  VARCHAR(40)  NOT NULL,
    contributor_id         VARCHAR(64)  NOT NULL REFERENCES app_user(id),
    assigned_to            VARCHAR(64)  REFERENCES app_user(id),
    written_form           TEXT         NOT NULL,
    part_of_speech         VARCHAR(5)   NOT NULL,
    synset_action          VARCHAR(10)  NOT NULL,
    synset_id              VARCHAR(100),
    ili_code               VARCHAR(50),
    definition_language    VARCHAR(5),
    definition_text        TEXT,
    sense_relations_json   TEXT,
    synset_relations_json  TEXT,
    reviewer_note          TEXT,
    editor_comment         TEXT,
    cle_comment            TEXT,
    rejection_reason       TEXT,
    validated_by           VARCHAR(64),
    validated_at           TIMESTAMPTZ,
    reward_eligible        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_submission_state ON submission (state);
CREATE INDEX idx_submission_contributor ON submission (contributor_id);
CREATE INDEX idx_submission_lemma_pos ON submission (written_form, part_of_speech);

CREATE TABLE state_transition (
    id            BIGSERIAL PRIMARY KEY,
    submission_id VARCHAR(64)  NOT NULL REFERENCES submission(id) ON DELETE CASCADE,
    from_state    VARCHAR(40),
    to_state      VARCHAR(40)  NOT NULL,
    actor_id      VARCHAR(64),
    actor_role    VARCHAR(40)  NOT NULL,
    comment_text  TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_transition_submission ON state_transition (submission_id);

CREATE TABLE arbitration_case (
    id                    VARCHAR(64)  PRIMARY KEY,
    submission_id         VARCHAR(64)  NOT NULL REFERENCES submission(id),
    claimant_id           VARCHAR(64)  NOT NULL REFERENCES app_user(id),
    accused_id            VARCHAR(64)  NOT NULL REFERENCES app_user(id),
    dispute_type          VARCHAR(40)  NOT NULL,
    dispute_note          TEXT         NOT NULL,
    counter_claim         TEXT,
    opened_at             TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    response_deadline     TIMESTAMPTZ  NOT NULL,
    status                VARCHAR(40)  NOT NULL,
    excluded_curator_ids  TEXT,
    final_verdict         VARCHAR(40),
    verdict_by            VARCHAR(20),
    tie_broken_by         VARCHAR(64),
    resolved_at           TIMESTAMPTZ,
    correction_applied    BOOLEAN      NOT NULL DEFAULT FALSE,
    correction_note       TEXT,
    pre_arbitration_state VARCHAR(40),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_arb_status ON arbitration_case (status);

CREATE TABLE arbitration_vote (
    id               BIGSERIAL PRIMARY KEY,
    case_id          VARCHAR(64)  NOT NULL REFERENCES arbitration_case(id) ON DELETE CASCADE,
    curator_id       VARCHAR(64)  NOT NULL REFERENCES app_user(id),
    verdict          VARCHAR(40)  NOT NULL,
    rationale        TEXT         NOT NULL,
    correction_note  TEXT,
    voted_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_arb_vote UNIQUE (case_id, curator_id)
);

-- Community ----------------------------------------------------------------
CREATE TABLE community_post (
    id           VARCHAR(64)  PRIMARY KEY,
    author_id    VARCHAR(64)  NOT NULL REFERENCES app_user(id),
    auto_post    BOOLEAN      NOT NULL DEFAULT FALSE,
    text_body    VARCHAR(500) NOT NULL,
    hidden       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_post_created ON community_post (created_at DESC);

CREATE TABLE post_reaction (
    id         BIGSERIAL PRIMARY KEY,
    post_id    VARCHAR(64) NOT NULL REFERENCES community_post(id) ON DELETE CASCADE,
    user_id    VARCHAR(64) NOT NULL REFERENCES app_user(id),
    reaction   VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_post_reaction UNIQUE (post_id, user_id)
);

CREATE TABLE post_reply (
    id         VARCHAR(64)  PRIMARY KEY,
    post_id    VARCHAR(64)  NOT NULL REFERENCES community_post(id) ON DELETE CASCADE,
    author_id  VARCHAR(64)  NOT NULL REFERENCES app_user(id),
    text_body  VARCHAR(200) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE post_report (
    id           BIGSERIAL PRIMARY KEY,
    post_id      VARCHAR(64) NOT NULL REFERENCES community_post(id) ON DELETE CASCADE,
    reporter_id  VARCHAR(64) NOT NULL REFERENCES app_user(id),
    reason       TEXT,
    reported_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_post_report UNIQUE (post_id, reporter_id)
);

-- Admin --------------------------------------------------------------------
CREATE TABLE audit_event (
    id           VARCHAR(64)  PRIMARY KEY,
    timestamp    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    actor_id     VARCHAR(64),
    actor_role   VARCHAR(40)  NOT NULL,
    action       VARCHAR(120) NOT NULL,
    target_type  VARCHAR(40)  NOT NULL,
    target_id    VARCHAR(64),
    detail       TEXT,
    ip_address   VARCHAR(64)
);
CREATE INDEX idx_audit_ts ON audit_event (timestamp DESC);
CREATE INDEX idx_audit_actor ON audit_event (actor_id);

CREATE TABLE system_config (
    id                         SMALLINT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    max_entries_per_session    INT NOT NULL DEFAULT 50,
    max_entries_per_day        INT NOT NULL DEFAULT 200,
    suspension_threshold       INT NOT NULL DEFAULT 500,
    min_submission_seconds     INT NOT NULL DEFAULT 10,
    anomaly_threshold_per_hour INT NOT NULL DEFAULT 100,
    ip_requests_per_minute     INT NOT NULL DEFAULT 10,
    session_timeout_minutes    INT NOT NULL DEFAULT 60,
    captcha_enabled            BOOLEAN NOT NULL DEFAULT TRUE,
    honeypot_enabled           BOOLEAN NOT NULL DEFAULT TRUE,
    captcha_score_threshold    DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    password_min_length        INT NOT NULL DEFAULT 10,
    reward_point_per_entry     INT NOT NULL DEFAULT 1,
    tier_gold_min              INT NOT NULL DEFAULT 500,
    tier_silver_min            INT NOT NULL DEFAULT 200,
    tier_bronze_min            INT NOT NULL DEFAULT 50,
    updated_at                 TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE export_record (
    id             VARCHAR(64) PRIMARY KEY,
    timestamp      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    actor_id       VARCHAR(64) NOT NULL,
    entry_count    INT NOT NULL,
    synset_count   INT NOT NULL,
    sha256         VARCHAR(64) NOT NULL,
    config_json    TEXT
);

CREATE TABLE id_sequence (
    name       VARCHAR(40) PRIMARY KEY,
    next_value BIGINT NOT NULL
);

CREATE TABLE wordnet_ili (
    ili          VARCHAR(50) PRIMARY KEY,
    pos          VARCHAR(5)  NOT NULL,
    words_csv    TEXT,
    gloss        TEXT
);
CREATE INDEX idx_wn_ili_pos ON wordnet_ili (pos);

-- Seed reference data
INSERT INTO sense_relation_type (relation_name) VALUES
 ('derivation'), ('antonym'), ('metonym');

INSERT INTO synset_relation_type (relation_name) VALUES
 ('hypernym'), ('mero_part'), ('mero_substance'), ('mero_member'), ('attribute');

INSERT INTO system_config (id) VALUES (1);

INSERT INTO id_sequence (name, next_value) VALUES
 ('submission', 1),
 ('case', 1),
 ('post', 1),
 ('reply', 1),
 ('user', 100),
 ('credential', 100),
 ('audit', 1),
 ('export', 1),
 ('word', 1),
 ('sense', 1),
 ('synset', 1);

INSERT INTO lexicon (
    id, label, language, email, license, version, dc_source, dc_description, dc_publisher
) VALUES (
    'livelanguage-ben',
    'Bengali',
    'ben',
    'gabor.bella@unitn.it',
    'https://creativecommons.org/licenses/by-nc-sa/4.0/',
    '1.0',
    'IndoWordNet; Wiktionary; MorphyNet',
    'Bengali mono-lingual lexicon for the Universal Knowledge Core (UKC).',
    'KnowDive — University of Trento'
);
