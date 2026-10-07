-- Microsoft throttles for minutes at a time (429), sometimes longer than the default patience of 5 tries with waits
-- of 10, 20, 30, 40 s (a crawl on 2026-10-07 14:38 gave up after 100 s). More patience for this company only:
-- 8 tries, waits of 30, 60, 90 ... 210 s between them (14 minutes in total) before the crawl gives up as PARTIAL.
-- The scheduler then tries the company again 30 minutes later (jobagent.crawl.schedule.retry-after). Every setting is
-- documented in CONFIGURATION.md; change it with an UPDATE of companies.config.
UPDATE companies
SET config = config || '{"coolDownSeconds": 30, "maxThrottledTries": 8, "maxCoolDownSeconds": 300}', updated_at = now()
WHERE slug = 'microsoft';
