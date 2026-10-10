-- Seed: Atlassian (2026-10-11). Its careers page loads one JSON list of every posting worldwide (with full text) from
-- /endpoint/careers/listings, which robots.txt allows (only the job pages under /company/careers/detail/ are not).
INSERT INTO companies (slug, name, platform, config, careers_url, notes) VALUES
    ('atlassian', 'Atlassian', 'atlassian', '{"country": "IN"}',
     'https://www.atlassian.com/company/careers/all-jobs',
     '47 India jobs on 2026-10-11 (354 worldwide, one request, no posting date).');
