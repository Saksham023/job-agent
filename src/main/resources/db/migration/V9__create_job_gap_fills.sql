-- Milestone 6: a model (Claude Opus) fills what the rules could not extract: experience when the rules found none
-- or only a weak guess (NONE / LOW), the family when it is UNCLASSIFIED, the main languages when none were found.
-- One row per job holds the model's answer for one version of the posting (content_hash) and of the prompt, so a
-- rules rebuild re-applies it without a new call, and a changed posting or prompt is asked again.
CREATE TABLE job_gap_fills (
    job_id          BIGINT      PRIMARY KEY REFERENCES jobs (id) ON DELETE CASCADE,
    content_hash    TEXT        NOT NULL,                  -- jobs.content_hash when it was asked
    prompt_version  TEXT        NOT NULL,
    model           TEXT        NOT NULL,
    answer          JSONB       NOT NULL,                  -- the model's whole answer, as given
    accepted        JSONB       NOT NULL,                  -- the gap fields that passed every check
    rejected        JSONB       NOT NULL DEFAULT '[]',     -- why the other gap fields were not used
    cost_usd        NUMERIC(10, 4),
    millis          INTEGER,
    filled_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Where each field came from: 'rules' or 'claude:<model>'. A rules rebuild resets them to 'rules' and then
-- re-applies the stored fill.
ALTER TABLE job_requirements
    ADD COLUMN years_source     TEXT NOT NULL DEFAULT 'rules',
    ADD COLUMN family_source    TEXT NOT NULL DEFAULT 'rules',
    ADD COLUMN languages_source TEXT NOT NULL DEFAULT 'rules';
