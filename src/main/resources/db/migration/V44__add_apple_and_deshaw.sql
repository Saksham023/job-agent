-- Apple and the D. E. Shaw group, each with its own adapter (2026-10-12).
-- Apple: the India search pages of jobs.apple.com (174 entries on 2026-10-12, one entry per city of a role; the
-- adapter merges them) and one job page per role for the full text. D. E. Shaw: one request to its India careers page,
-- which embeds every open position (75) with its full text.
INSERT INTO companies (slug, name, platform, config, careers_url, notes) VALUES
('apple',  'Apple',  'apple',  '{}', 'https://jobs.apple.com/en-in/search?location=india-INDC',
 '174 India entries on 2026-10-12 (software, Apple Retail store roles, operations). No robots.txt.'),
('deshaw', 'D. E. Shaw', 'deshaw', '{}', 'https://www.deshawindia.com/careers',
 '75 India positions on 2026-10-12 (about 30 tech; 6 "All positions in ..." general applications kept). No posting dates.');
