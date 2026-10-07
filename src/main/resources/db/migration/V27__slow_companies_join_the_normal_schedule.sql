-- Microsoft and Qualcomm join the normal schedule (2026-10-07, user's decision): every server group, including their own
-- one, runs with the same fixed delay (jobagent.crawl.schedule.delay). Gone: the once-a-day rule, the 03:00 window, the
-- hourly quick check for new jobs and the separate retry. What stays is the patience with throttling (V25), now also for
-- Qualcomm: 8 tries, waits of 30, 60, 90 ... seconds before a crawl gives up as PARTIAL (the next round, one delay later,
-- is the retry; stored descriptions are reused).
DELETE FROM crawl_runs WHERE kind = 'PEEK';
ALTER TABLE crawl_runs DROP COLUMN kind;

UPDATE companies
SET config = (config - 'minCrawlHours' - 'fullCrawlFromHour' - 'peekEveryMinutes' - 'newestSortBy' - 'peekMaxPages')
             || '{"coolDownSeconds": 30, "maxThrottledTries": 8, "maxCoolDownSeconds": 300}',
    updated_at = now()
WHERE slug IN ('microsoft', 'qualcomm');
