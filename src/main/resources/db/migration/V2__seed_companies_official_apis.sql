-- Seed: companies on platforms with official public job-board APIs (Milestone 1 adapters).
-- Source: research/sources_2026-10-04.json (India counts as of 2026-10-04 in notes).

INSERT INTO companies (slug, name, platform, config, careers_url, notes) VALUES
-- Greenhouse: GET https://boards-api.greenhouse.io/v1/boards/{boardToken}/jobs
('databricks', 'Databricks', 'greenhouse', '{"boardToken": "databricks"}',
 'https://job-boards.greenhouse.io/databricks', '95 India jobs on 2026-10-04. No server-side location filter.'),
('zscaler',    'Zscaler',    'greenhouse', '{"boardToken": "zscaler"}',
 'https://job-boards.greenhouse.io/zscaler',    '84 India jobs. Locations use "IND" as well as "India".'),
('razorpay',   'Razorpay',   'greenhouse', '{"boardToken": "razorpaysoftwareprivatelimited"}',
 'https://job-boards.greenhouse.io/razorpaysoftwareprivatelimited', '21 India jobs.'),
('groww',      'Groww',      'greenhouse', '{"boardToken": "groww"}',
 'https://job-boards.greenhouse.io/groww',      '7 India jobs.'),

-- Lever: GET https://api.lever.co/v0/postings/{site}?mode=json
('paytm',  'Paytm',  'lever', '{"site": "paytm"}',  'https://jobs.lever.co/paytm',  '161 India jobs. Some UAE roles.'),
('meesho', 'Meesho', 'lever', '{"site": "meesho"}', 'https://jobs.lever.co/meesho', '56 India jobs.'),
('cred',   'CRED',   'lever', '{"site": "cred"}',   'https://jobs.lever.co/cred',   '8 India jobs.'),
('zeta',   'Zeta',   'lever', '{"site": "zeta"}',   'https://jobs.lever.co/zeta',   '23 of 25 jobs in India.'),

-- SmartRecruiters: GET https://api.smartrecruiters.com/v1/companies/{companyId}/postings?country=in
('phonepe',    'PhonePe',    'smartrecruiters', '{"companyId": "PHONEPELIMITED"}',
 'https://careers.smartrecruiters.com/PHONEPELIMITED', '90 India jobs. Id "PhonePe" returns 0; use PHONEPELIMITED.'),
('servicenow', 'ServiceNow', 'smartrecruiters', '{"companyId": "ServiceNow"}',
 'https://careers.smartrecruiters.com/ServiceNow', '77 India jobs. careers.servicenow.com is 403 to scripts.'),
('freshworks', 'Freshworks', 'smartrecruiters', '{"companyId": "Freshworks"}',
 'https://careers.smartrecruiters.com/Freshworks', '31 India jobs. limit max 100, paginate with offset.'),

-- Ashby: GET https://api.ashbyhq.com/posting-api/job-board/{boardName}
('sarvam-ai', 'Sarvam AI', 'ashby', '{"boardName": "sarvam"}',
 'https://jobs.ashbyhq.com/sarvam', '59 jobs, all India, on 2026-10-04.');