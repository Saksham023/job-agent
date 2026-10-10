-- Twenty more well-paying companies (SDE2 around or above 30 LPA by public salary data) on platforms we already crawl
-- (2026-10-11). Found by a probe of ~60 companies; India counts are from that probe. No new adapter needed.
INSERT INTO companies (slug, name, platform, config, careers_url, notes) VALUES
-- Greenhouse: GET https://boards-api.greenhouse.io/v1/boards/{boardToken}/jobs
('okta',        'Okta',        'greenhouse', '{"boardToken": "okta"}',        'https://job-boards.greenhouse.io/okta',        '114 India jobs on 2026-10-11.'),
('mongodb',     'MongoDB',     'greenhouse', '{"boardToken": "mongodb"}',     'https://job-boards.greenhouse.io/mongodb',     '85 India jobs.'),
('inmobi',      'InMobi',      'greenhouse', '{"boardToken": "inmobi"}',      'https://job-boards.greenhouse.io/inmobi',      '37 India jobs.'),
('gitlab',      'GitLab',      'greenhouse', '{"boardToken": "gitlab"}',      'https://job-boards.greenhouse.io/gitlab',      '30 India jobs (mostly remote).'),
('glean',       'Glean',       'greenhouse', '{"boardToken": "gleanwork"}',   'https://job-boards.greenhouse.io/gleanwork',   '24 India jobs.'),
('squarepoint', 'Squarepoint Capital', 'greenhouse', '{"boardToken": "squarepointcapital"}', 'https://job-boards.greenhouse.io/squarepointcapital', '23 India jobs (trading firm).'),
('elastic',     'Elastic',     'greenhouse', '{"boardToken": "elastic"}',     'https://job-boards.greenhouse.io/elastic',     '18 India jobs.'),
('druva',       'Druva',       'greenhouse', '{"boardToken": "druva"}',       'https://job-boards.greenhouse.io/druva',       '13 India jobs.'),
('harness',     'Harness',     'greenhouse', '{"boardToken": "harnessinc"}',  'https://job-boards.greenhouse.io/harnessinc',  '8 India jobs.'),
('worldquant',  'WorldQuant',  'greenhouse', '{"boardToken": "worldquant"}',  'https://job-boards.greenhouse.io/worldquant',  '8 India jobs (trading firm).'),
('anthropic',   'Anthropic',   'greenhouse', '{"boardToken": "anthropic"}',   'https://job-boards.greenhouse.io/anthropic',   '4 India jobs (applied AI, sales).'),
('deliveroo',   'Deliveroo',   'greenhouse', '{"boardToken": "deliveroo"}',   'https://job-boards.greenhouse.io/deliveroo',   '4 India jobs.'),
-- SmartRecruiters: GET https://api.smartrecruiters.com/v1/companies/{companyId}/postings?country=in
('swiggy',      'Swiggy',      'smartrecruiters', '{"companyId": "swiggy"}',  'https://careers.smartrecruiters.com/swiggy',   '173 India jobs (tech and business).'),
('grab',        'Grab',        'smartrecruiters', '{"companyId": "grab"}',    'https://careers.smartrecruiters.com/grab',     '13 India jobs (Bengaluru).'),
-- Ashby: GET https://api.ashbyhq.com/posting-api/job-board/{boardName}
('tekion',      'Tekion',      'ashby', '{"boardName": "tekion"}',            'https://jobs.ashbyhq.com/tekion',              '76 India jobs.'),
('openai',      'OpenAI',      'ashby', '{"boardName": "openai"}',            'https://jobs.ashbyhq.com/openai',              '8 India jobs (applied AI, sales).'),
('atlan',       'Atlan',       'ashby', '{"boardName": "atlan"}',             'https://jobs.ashbyhq.com/atlan',               '5 India jobs.'),
-- Workday: POST https://{host}/wday/cxs/{tenant}/{site}/jobs
('morgan-stanley', 'Morgan Stanley', 'workday',
 '{"host": "ms.wd5.myworkdayjobs.com", "tenant": "ms", "site": "External"}',
 'https://ms.wd5.myworkdayjobs.com/External', '108 India jobs. Country facet Location_Country (no Eightfold session needed).'),
('ebay',        'eBay',        'workday',
 '{"host": "ebay.wd5.myworkdayjobs.com", "tenant": "ebay", "site": "apply"}',
 'https://ebay.wd5.myworkdayjobs.com/apply', '88 India jobs. Country facet Location_Country.'),
('crowdstrike', 'CrowdStrike', 'workday',
 '{"host": "crowdstrike.wd5.myworkdayjobs.com", "tenant": "crowdstrike", "site": "crowdstrikecareers"}',
 'https://crowdstrike.wd5.myworkdayjobs.com/crowdstrikecareers', '34 India jobs. Country facet locationCountry.');
