-- Applicants now supply the author details an account needs, so approval can prefill everything.
ALTER TABLE contribute_application
    ADD COLUMN occupation VARCHAR(80),
    ADD COLUMN dob        DATE;

-- One pending request per email address.
CREATE UNIQUE INDEX uq_contribute_application_pending_email
    ON contribute_application (lower(email))
    WHERE status = 'NEW';
