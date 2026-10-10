-- LinkedIn company ids, round 3 (2026-10-11), checked by hand by the user on LinkedIn: the companies added in V37.
-- Squarepoint Capital has none (it keeps the name search).
UPDATE companies c
SET config = c.config || jsonb_build_object('linkedinCompanyId', v.id)
FROM (VALUES
    ('swiggy', '9252341'), ('okta', '926041'), ('morgan-stanley', '497017'), ('ebay', '1481'), ('mongodb', '783611'),
    ('tekion', '41315'), ('inmobi', '104812249'), ('crowdstrike', '2497653'), ('gitlab', '5101804'), ('glean', '74882602'),
    ('elastic', '814025'), ('druva', '960262'), ('grab', '5382086'), ('harness', '18249313'), ('worldquant', '276383'),
    ('openai', '11130470'), ('atlan', '3374473'), ('anthropic', '74126343'), ('deliveroo', '2837535')
) AS v (slug, id)
WHERE c.slug = v.slug;
