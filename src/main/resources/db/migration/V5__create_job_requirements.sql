-- Facts derived from a job's title, department and description by our extractors (Milestone 2).
-- Kept apart from `jobs`: this data is recomputed whenever an extractor improves (extractor_version),
-- without re-crawling, and it disappears together with its job.

CREATE TABLE job_requirements (
                                  job_id               BIGINT      PRIMARY KEY REFERENCES jobs (id) ON DELETE CASCADE,
                                  extractor_version    INTEGER     NOT NULL,

    -- experience
                                  min_years            SMALLINT,
                                  max_years            SMALLINT,
                                  preferred_min_years  SMALLINT,
                                  years_evidence       TEXT,

    -- what kind of job
                                  seniority            TEXT,
                                  family               TEXT,
                                  specialization       TEXT,
                                  family_reasons       JSONB       NOT NULL DEFAULT '[]'::jsonb,
                                  employment_type      TEXT,

    -- skills (canonical names from the skill dictionary)
                                  required_skills      TEXT[]      NOT NULL DEFAULT '{}',
                                  preferred_skills     TEXT[]      NOT NULL DEFAULT '{}',
                                  primary_languages    TEXT[]      NOT NULL DEFAULT '{}',

                                  extracted_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

                                  CONSTRAINT job_requirements_years_order_chk CHECK (min_years IS NULL OR max_years IS NULL OR min_years <= max_years),
                                  CONSTRAINT job_requirements_years_range_chk CHECK (min_years IS NULL OR min_years BETWEEN 0 AND 40),
                                  CONSTRAINT job_requirements_reasons_array_chk CHECK (jsonb_typeof(family_reasons) = 'array')
);

-- Filters used by matching: "tech families only", "jobs requiring Java".
CREATE INDEX job_requirements_family_idx          ON job_requirements (family);
CREATE INDEX job_requirements_required_skills_idx ON job_requirements USING GIN (required_skills);