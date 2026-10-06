-- Milestone 8: search with the Opus judge.
-- judgments: one verdict per (candidate profile, job, rubric version, job content). Kept forever: verdicts cost money,
-- and the same profile searching again reuses them. A changed posting (content_hash) or a new rubric asks again.
CREATE TABLE judgments (
                           profile_hash    TEXT        NOT NULL,                  -- hash of the judge-relevant profile fields
                           job_id          BIGINT      NOT NULL REFERENCES jobs (id) ON DELETE CASCADE,
                           rubric_version  TEXT        NOT NULL,
                           content_hash    TEXT        NOT NULL,                  -- jobs.content_hash when judged
                           model           TEXT        NOT NULL,
                           verdict         TEXT        NOT NULL CHECK (verdict IN ('APPLY', 'MAYBE', 'NO')),
                           role_fit        TEXT,
                           experience_fit  TEXT,
                           stack_fit       TEXT,
                           reason          TEXT,
                           cost_usd        NUMERIC(10, 4),
                           millis          INTEGER,
                           judged_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
                           PRIMARY KEY (profile_hash, job_id, rubric_version, content_hash)
);

CREATE INDEX judgments_job_idx ON judgments (job_id);

-- searches: one row per search. candidate_ids is the rule shortlist in score order (best first) and candidate_scores
-- their rule scores (same positions); shown_ids are the jobs already returned to the user. "Ready" jobs (judged APPLY,
-- not shown), the next job to judge and the NO streak are all computed from these two tables, so a background judge
-- can stop and resume at any time, even after a restart.
CREATE TABLE searches (
                          id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
                          profile          JSONB       NOT NULL,                 -- the request and the candidate as the judge sees it
                          profile_hash     TEXT        NOT NULL,
                          rubric_version   TEXT        NOT NULL,
                          candidate_ids    BIGINT[]    NOT NULL,
                          candidate_scores INTEGER[]   NOT NULL,
                          shown_ids        BIGINT[]    NOT NULL DEFAULT '{}',
                          created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
                          last_used_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
                          CONSTRAINT searches_scores_chk CHECK (cardinality(candidate_ids) = cardinality(candidate_scores))
);