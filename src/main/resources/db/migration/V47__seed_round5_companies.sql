-- Eighteen more companies from the user's Tier 1 / 1.5 / 2 list (2026-10-13), all on platforms we already crawl, so no
-- new adapter. The user wants them even when they have few or no India openings today: the board is real, and jobs
-- show up when they hire. India counts are from probe round 5.
INSERT INTO companies (slug, name, platform, config, careers_url, notes) VALUES
-- Greenhouse: GET https://boards-api.greenhouse.io/v1/boards/{boardToken}/jobs
('doordash',       'DoorDash',       'greenhouse', '{"boardToken": "doordashindia"}',          'https://job-boards.greenhouse.io/doordashindia',          '18 India jobs on 2026-10-13. DoorDash has one board per region; doordashindia holds the India jobs.'),
('hackerrank',     'HackerRank',     'greenhouse', '{"boardToken": "hackerrank"}',             'https://job-boards.greenhouse.io/hackerrank',             '18 India jobs.'),
('couchbase',      'Couchbase',      'greenhouse', '{"boardToken": "couchbaseinc"}',           'https://job-boards.greenhouse.io/couchbaseinc',           '10 India jobs (Bengaluru).'),
('apollo-io',      'Apollo.io',      'greenhouse', '{"boardToken": "apolloio"}',               'https://job-boards.greenhouse.io/apolloio',               '6 India jobs.'),
('yugabyte',       'Yugabyte',       'greenhouse', '{"boardToken": "yugabyte"}',               'https://job-boards.greenhouse.io/yugabyte',               '5 India jobs.'),
('bitgo',          'BitGo',          'greenhouse', '{"boardToken": "bitgo"}',                  'https://job-boards.greenhouse.io/bitgo',                  '~4 India jobs.'),
('cockroach-labs', 'Cockroach Labs', 'greenhouse', '{"boardToken": "cockroachlabs"}',          'https://job-boards.greenhouse.io/cockroachlabs',          '2 India jobs.'),
('tessell',        'Tessell',        'greenhouse', '{"boardToken": "tessellinc"}',             'https://job-boards.greenhouse.io/tessellinc',             '0 India jobs on 2026-10-13 (added for when it hires).'),
('prophecy',       'Prophecy',       'greenhouse', '{"boardToken": "prophecysimpledatalabs"}', 'https://job-boards.greenhouse.io/prophecysimpledatalabs', '0 India jobs on 2026-10-13.'),
('compass',        'Compass',        'greenhouse', '{"boardToken": "urbancompass"}',           'https://job-boards.greenhouse.io/urbancompass',           '0 India jobs on 2026-10-13; India offices in Hyderabad, Bengaluru, Gurugram.'),
-- Ashby: GET https://api.ashbyhq.com/posting-api/job-board/{boardName}
('nightfall-ai',   'Nightfall AI',   'ashby', '{"boardName": "nightfall-ai"}', 'https://jobs.ashbyhq.com/nightfall-ai', '16 India jobs (mostly engineering).'),
('uipath',         'UiPath',         'ashby', '{"boardName": "uipath"}',       'https://jobs.ashbyhq.com/uipath',       '8 India jobs.'),
('lg-ads',         'LG Ads (Alphonso)', 'ashby', '{"boardName": "lgads"}',     'https://jobs.ashbyhq.com/lgads',        '2 India jobs. Alphonso is now LG Ads.'),
-- SmartRecruiters: GET https://api.smartrecruiters.com/v1/companies/{companyId}/postings?country=in
('cars24',         'Cars24',         'smartrecruiters', '{"companyId": "cars24"}', 'https://careers.smartrecruiters.com/cars24', '1 India job.'),
-- Workday
('jiostar',        'JioStar (Hotstar)', 'workday',
 '{"host": "jiostar.wd102.myworkdayjobs.com", "tenant": "jiostar", "site": "JioStar", "singleCountry": true}',
 'https://jiostar.wd102.myworkdayjobs.com/JioStar', '238 jobs, all India (offices like "Bengaluru- We Work", "Mumbai - OUC"); 111 in Product, Technology and Engineering. singleCountry: some postings have a blank location in the list.'),
('target',         'Target',         'workday',
 '{"host": "target.wd5.myworkdayjobs.com", "tenant": "target", "site": "targetcareers"}',
 'https://target.wd5.myworkdayjobs.com/targetcareers', '57 India jobs (Bangalore). Country facet Location_Country.'),
('cloudera',       'Cloudera',       'workday',
 '{"host": "cloudera.wd5.myworkdayjobs.com", "tenant": "cloudera", "site": "External_Career"}',
 'https://cloudera.wd5.myworkdayjobs.com/External_Career', '1 India job. Country facet locationCountry.'),
-- Oracle Recruiting Cloud: the India location id is discovered from the LOCATIONS facet
('dp-world',       'DP World',       'oracle',
 '{"host": "ehpv.fa.em2.oraclecloud.com", "siteNumber": "CX_1"}',
 'https://ehpv.fa.em2.oraclecloud.com/hcmUI/CandidateExperience/en/sites/CX_1', '151 India jobs, mostly ports and logistics.');
