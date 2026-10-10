-- Atlassian's LinkedIn company id (2026-10-11), checked by hand by the user on LinkedIn (a Jobs search filtered to Atlassian:
-- f_C=22688). Its own migration because V35 had already run on the local database.
UPDATE companies SET config = config || '{"linkedinCompanyId": "22688"}' WHERE slug = 'atlassian';
