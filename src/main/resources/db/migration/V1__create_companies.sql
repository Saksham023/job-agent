-- Company registry: one row per company we crawl.
-- Platform-specific settings live in `config` (JSONB); each adapter parses and validates its own shape.

CREATE TABLE companies (
                           id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                           slug        TEXT        NOT NULL UNIQUE,
                           name        TEXT        NOT NULL,
                           platform    TEXT        NOT NULL,
                           config      JSONB       NOT NULL DEFAULT '{}'::jsonb,
                           careers_url TEXT,
                           enabled     BOOLEAN     NOT NULL DEFAULT TRUE,
                           notes       TEXT,
                           created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
                           updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

                           CONSTRAINT companies_slug_format_chk CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT companies_platform_format_chk CHECK (platform ~ '^[a-z0-9_]+$'),
    CONSTRAINT companies_config_is_object_chk CHECK (jsonb_typeof(config) = 'object')
);

CREATE INDEX companies_platform_enabled_idx ON companies (platform) WHERE enabled;