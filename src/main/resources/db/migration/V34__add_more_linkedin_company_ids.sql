-- LinkedIn company ids, round 2 (2026-10-11), checked by hand by the user on LinkedIn: the 12 companies added in V33 and
-- Samsung. Several ids = the button searches all of them: Broadcom + VMware; Samsung Electronics + Samsung R&D Institute
-- India pages.
UPDATE companies c
SET config = c.config || jsonb_build_object('linkedinCompanyId', v.id)
FROM (VALUES
    ('palo-alto-networks', '30086'), ('pure-storage', '1632202'), ('broadcom', '3072,2988'), ('stripe', '2135371'),
    ('rubrik', '4840301'), ('twilio', '400528'), ('graviton', '9329182'), ('tower-research', '36865'),
    ('snowflake', '3653845'), ('cohesity', '3750699'), ('coinbase', '2857634'), ('airbnb', '309694'),
    ('samsung', '1753,33926293,106767085')
) AS v (slug, id)
WHERE c.slug = v.slug;
