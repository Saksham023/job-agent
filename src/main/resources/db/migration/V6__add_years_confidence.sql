-- How sure the experience extractor was (HIGH / MEDIUM / LOW / NONE). Needed by the coverage report and,
-- later, by the extraction cascade, which sends LOW and NONE results to a model for a second opinion.
ALTER TABLE job_requirements
    ADD COLUMN years_confidence TEXT NOT NULL DEFAULT 'NONE';