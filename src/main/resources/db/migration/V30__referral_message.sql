-- Referral message (2026-10-10). The message is a TEMPLATE filled in the browser for each job from the profile and the
-- job; no AI writes it. The headline ("backend engineer") comes from the same model call that reads the resume.
ALTER TABLE user_profiles ADD COLUMN headline TEXT;   -- the person's broad role, e.g. "backend engineer"; NULL = unknown

-- Only users who rewrote the message have a row; everyone else gets the default template from the code.
CREATE TABLE referral_templates (
    user_id     BIGINT      PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    text        TEXT        NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
