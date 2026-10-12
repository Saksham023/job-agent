-- LinkedIn company ids, round 5 (2026-10-13), checked by hand by the user on LinkedIn: the companies added in V47.
-- JioStar has two pages (JioStar and JioHotstar); the connections button searches both.
UPDATE companies c
SET config = c.config || jsonb_build_object('linkedinCompanyId', v.id)
FROM (VALUES
    ('jiostar', '105902913,105490663'), ('doordash', '3205573'), ('target', '1512'), ('hackerrank', '435210'),
    ('nightfall-ai', '19031754'), ('couchbase', '1866670'), ('uipath', '1523656'), ('apollo-io', '18511550'),
    ('yugabyte', '10643910'), ('bitgo', '3597797'), ('cockroach-labs', '9309408'), ('lg-ads', '3100031'),
    ('cloudera', '229433'), ('cars24', '10429660'), ('tessell', '72621157'), ('prophecy', '7953678'),
    ('compass', '2866215'), ('dp-world', '163494')
) AS v (slug, id)
WHERE c.slug = v.slug;
