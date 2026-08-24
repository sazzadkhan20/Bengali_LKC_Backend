-- Ensure UTF-8 and extensions suitable for Bengali text
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
CREATE EXTENSION IF NOT EXISTS "unaccent";

-- Default privileges (role already created by POSTGRES_* env)
ALTER DATABASE bengali_lkc SET timezone TO 'UTC';
