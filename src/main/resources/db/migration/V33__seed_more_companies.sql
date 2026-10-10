-- Twelve more companies on platforms we already crawl (2026-10-10); no new adapter needed. Checked with one request each
-- (India counts on 2026-10-10). Not found on our platforms: Rippling, Nutanix, Arista, D. E. Shaw India (own sites).
INSERT INTO companies (slug, name, platform, config, careers_url, notes) VALUES
-- Greenhouse: GET https://boards-api.greenhouse.io/v1/boards/{boardToken}/jobs
('pure-storage', 'Pure Storage', 'greenhouse', '{"boardToken": "purestorage"}',
 'https://job-boards.greenhouse.io/purestorage', '81 India jobs on 2026-10-10.'),
('stripe',       'Stripe',       'greenhouse', '{"boardToken": "stripe"}',
 'https://job-boards.greenhouse.io/stripe', '46 India jobs (728 worldwide).'),
('rubrik',       'Rubrik',       'greenhouse', '{"boardToken": "rubrik"}',
 'https://job-boards.greenhouse.io/rubrik', '36 India jobs.'),
('twilio',       'Twilio',       'greenhouse', '{"boardToken": "twilio"}',
 'https://job-boards.greenhouse.io/twilio', '20 India jobs.'),
('graviton',     'Graviton Research Capital', 'greenhouse', '{"boardToken": "gravitonresearchcapital"}',
 'https://job-boards.greenhouse.io/gravitonresearchcapital', '16 India jobs (trading firm).'),
('tower-research', 'Tower Research Capital', 'greenhouse', '{"boardToken": "towerresearchcapital"}',
 'https://job-boards.greenhouse.io/towerresearchcapital', '11 India jobs (trading firm).'),
('coinbase',     'Coinbase',     'greenhouse', '{"boardToken": "coinbase"}',
 'https://job-boards.greenhouse.io/coinbase', '10 India jobs.'),
('airbnb',       'Airbnb',       'greenhouse', '{"boardToken": "airbnb"}',
 'https://job-boards.greenhouse.io/airbnb', '5 India jobs.'),
-- Ashby: GET https://api.ashbyhq.com/posting-api/job-board/{boardName}
('snowflake',    'Snowflake',    'ashby', '{"boardName": "snowflake"}',
 'https://jobs.ashbyhq.com/snowflake', '12 India jobs (358 worldwide).'),
-- Workday: POST https://{host}/wday/cxs/{tenant}/{site}/jobs
('palo-alto-networks', 'Palo Alto Networks', 'workday',
 '{"host": "paloaltonetworks.wd5.myworkdayjobs.com", "tenant": "paloaltonetworks", "site": "panwexternalcareers"}',
 'https://paloaltonetworks.wd5.myworkdayjobs.com/panwexternalcareers',
 '~177 India jobs. No country facet: office entries "Office - India - Bangalore Bagmane Tech Park" in locationMainGroup.'),
('broadcom',     'Broadcom',     'workday',
 '{"host": "broadcom.wd1.myworkdayjobs.com", "tenant": "broadcom", "site": "External_Career"}',
 'https://broadcom.wd1.myworkdayjobs.com/External_Career',
 '~60 India jobs (VMware included). No country facet: office entries "IND-Bangalore Electronic City - S1".'),
('cohesity',     'Cohesity',     'workday',
 '{"host": "cohesity.wd5.myworkdayjobs.com", "tenant": "cohesity", "site": "Cohesity_Careers"}',
 'https://cohesity.wd5.myworkdayjobs.com/Cohesity_Careers', '12 India jobs. Country facet locationCountry.');
