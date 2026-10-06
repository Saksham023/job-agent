-- Incremental details (2026-10-06). Workday, Eightfold, Oracle and SmartRecruiters need one detail request per job for
-- its description. A known job is fetched again only when its list entry changed (list_hash: a fingerprint of the
-- list's stable fields, e.g. title and locations) or its detail is older than jobagent.crawl.detail-max-age-days (7);
-- otherwise the stored detail (jobs.raw) is reused.
ALTER TABLE jobs ADD COLUMN list_hash TEXT;                    -- null until the first crawl with this feature
ALTER TABLE jobs ADD COLUMN detail_fetched_at TIMESTAMPTZ;     -- when the stored detail (raw) was downloaded

-- Existing jobs: a stored description on these platforms came from a detail, downloaded at their last crawl.
UPDATE jobs j SET detail_fetched_at = j.last_seen_at
    FROM companies c
WHERE c.id = j.company_id AND c.platform IN ('workday', 'eightfold', 'oracle', 'smartrecruiters')
  AND j.description IS NOT NULL;

-- How many details a crawl downloaded and how many it reused (the saving, measured).
ALTER TABLE crawl_runs ADD COLUMN details_fetched INTEGER NOT NULL DEFAULT 0;
ALTER TABLE crawl_runs ADD COLUMN details_reused INTEGER NOT NULL DEFAULT 0;