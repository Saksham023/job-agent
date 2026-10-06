-- Self-learning skill dictionary (2026-10-06). Code finds candidate terms in job postings (unknown items in lists that
-- already name known skills), Opus decides what each one is, code checks the answer and applies it.

-- Every candidate term ever found, with what was decided about it (so a term is never asked twice).
CREATE TABLE skill_candidates (
                                  term_key     TEXT        PRIMARY KEY,               -- lower case, single spaces
                                  term         TEXT        NOT NULL,                  -- as most often written
                                  jobs         INTEGER     NOT NULL,                  -- open jobs mentioning it in a skill list
                                  companies    INTEGER     NOT NULL,                  -- distinct companies
                                  contexts     JSONB       NOT NULL DEFAULT '[]',     -- example sentences, different companies
                                  status       TEXT        NOT NULL DEFAULT 'NEW'
                                                   CHECK (status IN ('NEW', 'NEW_SKILL', 'ALIAS', 'REJECTED')),
                                  skill        TEXT,                                  -- the canonical skill (NEW_SKILL / ALIAS)
                                  category     TEXT,
                                  spellings    TEXT[]      NOT NULL DEFAULT '{}',
                                  ambiguous    BOOLEAN     NOT NULL DEFAULT false,
                                  reason       TEXT,                                  -- the model's reason, or why a check failed
                                  model        TEXT,
                                  first_seen   TIMESTAMPTZ NOT NULL DEFAULT now(),
                                  last_seen    TIMESTAMPTZ NOT NULL DEFAULT now(),
                                  decided_at   TIMESTAMPTZ,
                                  applied_at   TIMESTAMPTZ
);

CREATE INDEX skill_candidates_status_idx ON skill_candidates (status, companies DESC);

-- Learned spellings, loaded by SkillExtractor on top of skills.csv (the CSV is never edited). Undo = active false.
CREATE TABLE learned_skills (
                                skill        TEXT        NOT NULL,                  -- canonical name ("Dynatrace", "AWS Lambda")
                                category     TEXT        NOT NULL,
                                alias        TEXT        NOT NULL,                  -- one spelling ("dynatrace", "lambda")
                                ambiguous    BOOLEAN     NOT NULL DEFAULT false,    -- an ordinary word too: needs a skill nearby
                                source_term  TEXT        REFERENCES skill_candidates (term_key),
                                active       BOOLEAN     NOT NULL DEFAULT true,
                                created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
                                PRIMARY KEY (skill, alias)
);
