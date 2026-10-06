-- Seed: Amazon (2026-10-06). amazon.jobs has its own site; its search page calls /en/search.json, which filters by
-- country on the server and returns each job's full text. robots.txt allows it (only /internal is disallowed).
INSERT INTO companies (slug, name, platform, config, careers_url, notes) VALUES
    ('amazon', 'Amazon', 'amazon', '{"countryCode": "IND", "delayMs": 1000}',
     'https://www.amazon.jobs/en/search?country=IND', '2,312 India jobs on 2026-10-06 (100 per page, no detail calls).');