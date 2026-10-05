-- Seed: more companies on Workday (2026-10-06), chosen by the user: well-known product companies that pay well for
-- software roles in India. Found by trying each company's Workday site with one read-only list request; the adapter
-- discovers the India filter itself. India counts are what the filters showed that day.
-- Checked but not added (user: too much noise): Target, HP, Micron, Cadence, Analog Devices, Marvell, Thomson
-- Reuters, State Street, Motorola Solutions, LSEG, Philips, Fidelity, Lowe's, KLA, Applied Materials, Nasdaq,
-- S&P Global, PTC, Accenture; Broadcom (locations do not parse yet); Walmart, Dell, NetApp, Western Digital, Seagate,
-- Arm (site names unknown).

INSERT INTO companies (slug, name, platform, config, careers_url, notes) VALUES
                                                                             ('sprinklr',    'Sprinklr',    'workday', '{"host": "sprinklr.wd1.myworkdayjobs.com", "tenant": "sprinklr", "site": "careers"}',
                                                                              'https://sprinklr.wd1.myworkdayjobs.com/careers', '18 India jobs (locationCountry).'),
                                                                             ('blackrock',   'BlackRock',   'workday', '{"host": "blackrock.wd1.myworkdayjobs.com", "tenant": "blackrock", "site": "BlackRock_Professional"}',
                                                                              'https://blackrock.wd1.myworkdayjobs.com/BlackRock_Professional', '~41 India jobs. City entries only (4 India locations).'),
                                                                             ('wells-fargo', 'Wells Fargo', 'workday', '{"host": "wf.wd1.myworkdayjobs.com", "tenant": "wf", "site": "WellsFargoJobs"}',
                                                                              'https://wf.wd1.myworkdayjobs.com/WellsFargoJobs', '95 India jobs (locationCountry).'),
                                                                             ('autodesk',    'Autodesk',    'workday', '{"host": "autodesk.wd1.myworkdayjobs.com", "tenant": "autodesk", "site": "Ext"}',
                                                                              'https://autodesk.wd1.myworkdayjobs.com/Ext', '33 India jobs (locationCountry).'),
                                                                             ('workday',     'Workday',     'workday', '{"host": "workday.wd5.myworkdayjobs.com", "tenant": "workday", "site": "Workday"}',
                                                                              'https://workday.wd5.myworkdayjobs.com/Workday', '21 India jobs (Location_Country).'),
                                                                             ('ciena',       'Ciena',       'workday', '{"host": "ciena.wd5.myworkdayjobs.com", "tenant": "ciena", "site": "Careers"}',
                                                                              'https://ciena.wd5.myworkdayjobs.com/Careers', '6 India jobs (Location_Country).');