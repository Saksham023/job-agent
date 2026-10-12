-- Nine more well-paying companies (trading firms and product companies) on platforms we already crawl (2026-10-12).
-- Found by probe round 3; India counts are from that probe. No new adapter needed. Uniphore, Mindtickle and UKG were
-- probed too and left out by choice (mid pay or mostly non-tech roles).
INSERT INTO companies (slug, name, platform, config, careers_url, notes) VALUES
-- Greenhouse: GET https://boards-api.greenhouse.io/v1/boards/{boardToken}/jobs
('arcesium',      'Arcesium',      'greenhouse', '{"boardToken": "arcesiumllc"}',          'https://job-boards.greenhouse.io/arcesiumllc',          '22 India jobs on 2026-10-12 (about half finance roles).'),
('imc',           'IMC Trading',   'greenhouse', '{"boardToken": "imc"}',                  'https://job-boards.greenhouse.io/imc',                  '18 India jobs (trading firm, about half tech).'),
('optiver',       'Optiver',       'greenhouse', '{"boardToken": "optiverus"}',            'https://job-boards.greenhouse.io/optiverus',            '4 India jobs (trading firm).'),
('alphagrep',     'AlphaGrep',     'greenhouse', '{"boardToken": "alphagrepsecurities"}',  'https://job-boards.greenhouse.io/alphagrepsecurities',  '6 India jobs (trading firm).'),
('nk-securities', 'NK Securities', 'greenhouse', '{"boardToken": "nksecuritiesresearch"}', 'https://job-boards.greenhouse.io/nksecuritiesresearch', '13 India jobs (trading firm).'),
('observe-ai',    'Observe.AI',    'greenhouse', '{"boardToken": "observeai"}',            'https://job-boards.greenhouse.io/observeai',            '5 India jobs.'),
('agoda',         'Agoda',         'greenhouse', '{"boardToken": "agoda"}',                'https://job-boards.greenhouse.io/agoda',                '12 India jobs (backend roles in Gurgaon).'),
-- Workday: country from the office names ("India", "Pune, India", "India - Karnataka - Fully Flexible"; never "Indiana")
('zendesk',       'Zendesk',       'workday',
 '{"host": "zendesk.wd1.myworkdayjobs.com", "tenant": "zendesk", "site": "zendesk"}',
 'https://zendesk.wd1.myworkdayjobs.com/zendesk', 'About 28 India jobs. No country facet: India office entries in locations.'),
-- Eightfold: GET https://{host}/api/pcsx/search?domain={domain}&location=India
('eightfold-ai',  'Eightfold AI',  'eightfold',
 '{"host": "app.eightfold.ai", "domain": "eightfold.ai"}',
 'https://app.eightfold.ai/careers?location=India', '23 India jobs (Eightfold''s own openings).');
