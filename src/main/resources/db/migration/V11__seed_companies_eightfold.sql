-- Seed: companies on Eightfold (Milestone 7b). The adapter calls the PCSX API the public career pages use:
-- GET https://{host}/api/pcsx/search?domain={domain}&location=India&start=N, then one position_details call per job.
-- No fixed pace: every crawl starts at one request per second and slows down by itself on each 429 (Microsoft
-- answered 429 at 1 s and at 2 s per request on 2026-10-06; Qualcomm twice at 1 s). config.delayMs could set a
-- different starting pace.
-- Morgan Stanley and UKG need the page's session cookie + CSRF token and are not seeded yet.

INSERT INTO companies (slug, name, platform, config, careers_url, notes) VALUES
                                                                             ('microsoft', 'Microsoft', 'eightfold',
                                                                              '{"host": "apply.careers.microsoft.com", "domain": "microsoft.com"}',
                                                                              'https://apply.careers.microsoft.com/careers?location=India',
                                                                              '234 India jobs on 2026-10-06. Rate limits hard: 429s at 1-2 s per request, then refused connections.'),
                                                                             ('qualcomm',  'Qualcomm',  'eightfold',
                                                                              '{"host": "careers.qualcomm.com", "domain": "qualcomm.com"}',
                                                                              'https://careers.qualcomm.com/careers?location=India',
                                                                              '584 India jobs on 2026-10-06, many hardware roles (ASIC, physical design, RFIC). Two 429s at 1 s per request.');