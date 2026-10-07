-- Two kinds of crawl run (2026-10-07). FULL walks the company's whole list: only it judges health, closes vanished jobs
-- and sets the minimum interval. PEEK is the quick hourly check for NEW jobs of a slow company (the list sorted newest
-- first, stopping at the first page with nothing new): it never closes anything and never counts as a "good crawl" for
-- closing, because it does not see the older jobs and would make them look missing.
ALTER TABLE crawl_runs ADD COLUMN kind TEXT NOT NULL DEFAULT 'FULL' CHECK (kind IN ('FULL', 'PEEK'));

-- Microsoft and Qualcomm: a FULL crawl once a day from 03:00 India time (least throttling), at least 6 hours apart (only a
-- safety gap), and a PEEK every 60 minutes between (list sorted by "timestamp", at most 5 list pages per check).
-- Qualcomm's list carries only a date as its posting time, so its newest-first order is by day; the stop rule still works.
-- Every key is documented in CONFIGURATION.md.
UPDATE companies
SET config = config || '{"minCrawlHours": 6, "fullCrawlFromHour": 3, "peekEveryMinutes": 60, "newestSortBy": "timestamp", "peekMaxPages": 5}',
    updated_at = now()
WHERE slug IN ('microsoft', 'qualcomm');
