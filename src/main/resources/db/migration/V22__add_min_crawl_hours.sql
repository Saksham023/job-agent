-- Per-company crawl rhythm (2026-10-06). A full crawl run (scheduled every 6 h, or POST /admin/crawl) skips a company
-- whose last crawl started less than config.minCrawlHours ago. Eightfold sites throttle repeated full scans:
-- Qualcomm answered 429 down to one request every 13 s on its third crawl of a day, Microsoft blocked us on the first.
-- So both are crawled at most once a day (Microsoft stays disabled for now).
UPDATE companies SET config = config || '{"minCrawlHours": 24}', updated_at = now()
WHERE slug IN ('qualcomm', 'microsoft');
