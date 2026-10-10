-- A signed-in user's own profile (2026-10-10), read from their resume and editable by them. Separate from `profiles`
-- (the MCP search's frozen profiles, which are never edited so their cached AI verdicts stay valid).
-- The resume file itself is never stored: it is read once, the facts below are kept, the PDF is discarded.
CREATE TABLE user_profiles (
    user_id         BIGINT      PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    years           NUMERIC(4, 1),                       -- professional experience, decimal (1.6); NULL = unknown
    main_languages  TEXT[]      NOT NULL DEFAULT '{}',   -- programming languages the person mainly works in
    skills          TEXT[]      NOT NULL DEFAULT '{}',   -- most relevant first
    roles_wanted    TEXT,                                -- e.g. "Backend or AI engineering roles"
    families        TEXT[]      NOT NULL DEFAULT '{}',   -- job families to search (JobFamily names)
    drive_link      TEXT,                                -- the resume's Google Drive link, used in referral messages
    source          TEXT        NOT NULL CHECK (source IN ('drive', 'upload', 'manual')),
    read_at         TIMESTAMPTZ,                         -- when a resume was last read
    edited_at       TIMESTAMPTZ,                         -- when the user last changed the facts by hand
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
