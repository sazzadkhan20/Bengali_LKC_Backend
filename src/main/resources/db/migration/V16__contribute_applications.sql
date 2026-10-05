-- Public "Apply to contribute" requests, reviewed by the Platform Architect.
-- Approval issues a real account (Credential Management) linked back via issued_user_id.
CREATE TABLE contribute_application (
    id              VARCHAR(40)  PRIMARY KEY,
    name            VARCHAR(80)  NOT NULL,
    email           VARCHAR(120) NOT NULL,
    phone           VARCHAR(30)  NOT NULL,
    institution     VARCHAR(120),
    interest_role   VARCHAR(40),
    message         VARCHAR(800) NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'NEW',
    review_note     VARCHAR(500),
    reviewed_by     VARCHAR(100) REFERENCES app_user (id),
    reviewed_at     TIMESTAMPTZ,
    issued_user_id  VARCHAR(100) REFERENCES app_user (id),
    client_ip       VARCHAR(64),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_contribute_application_status CHECK (status IN ('NEW', 'APPROVED', 'REJECTED')),
    CONSTRAINT chk_contribute_application_role CHECK (
        interest_role IS NULL OR interest_role IN
            ('CONTRIBUTOR', 'LEXICAL_EDITOR', 'CHIEF_LEXICAL_EDITOR', 'ARBITRATION_CURATOR', 'OTHER'))
);

CREATE INDEX idx_contribute_application_status ON contribute_application (status, created_at DESC);
CREATE INDEX idx_contribute_application_email ON contribute_application (lower(email), created_at DESC);
CREATE INDEX idx_contribute_application_ip ON contribute_application (client_ip, created_at DESC);
