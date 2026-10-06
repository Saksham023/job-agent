-- Microsoft's first load (2026-10-06). It throttled us at one request every 1 to 2 s (429, then refused connections),
-- so it is crawled at one request every 10 s: 26 list pages + 253 details is about 47 minutes, once. The adaptive pace
-- still slows down further on a 429. minCrawlHours 24 (V22) keeps it to one crawl a day afterwards.
-- For a gentler first load spread over days instead, add "maxDetailsPerCrawl": 40 (details left over are fetched by the
-- next crawls).
UPDATE companies SET config = config || '{"delayMs": 10000}', enabled = true, updated_at = now()
WHERE slug = 'microsoft';
