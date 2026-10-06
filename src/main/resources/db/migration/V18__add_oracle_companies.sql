-- Seed: Oracle Recruiting Cloud tenants (2026-10-06). The adapter finds each tenant's "India" location id in the
-- LOCATIONS facet. India counts are what the filter showed that day. Oracle itself (eeho.fa.us2, CX_45001) is not
-- added: its facet lists only its top 18 locations and India (~15 jobs) is not among them.
-- JPMorgan's robots.txt path answers 403 from its firewall (the other tenants have none: 404); the API the public
-- careers page calls answers normally.
INSERT INTO companies (slug, name, platform, config, careers_url, notes) VALUES
                                                                             ('jpmorgan', 'JPMorgan Chase', 'oracle', '{"host": "jpmc.fa.oraclecloud.com", "siteNumber": "CX_1001"}',
                                                                              'https://jpmc.fa.oraclecloud.com/hcmUI/CandidateExperience/en/sites/CX_1001', '317 India jobs.'),
                                                                             ('texas-instruments', 'Texas Instruments', 'oracle', '{"host": "edbz.fa.us2.oraclecloud.com", "siteNumber": "CX"}',
                                                                              'https://edbz.fa.us2.oraclecloud.com/hcmUI/CandidateExperience/en/sites/CX', '133 India jobs.'),
                                                                             ('goldman-sachs', 'Goldman Sachs', 'oracle', '{"host": "hdpc.fa.us2.oraclecloud.com", "siteNumber": "LateralHiring"}',
                                                                              'https://hdpc.fa.us2.oraclecloud.com/hcmUI/CandidateExperience/en/sites/LateralHiring', '87 India jobs (lateral hiring site).'),
                                                                             ('amex', 'American Express', 'oracle', '{"host": "egug.fa.us2.oraclecloud.com", "siteNumber": "CX_1"}',
                                                                              'https://egug.fa.us2.oraclecloud.com/hcmUI/CandidateExperience/en/sites/CX_1', '65 India jobs.');