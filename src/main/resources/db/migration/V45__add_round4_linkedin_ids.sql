-- LinkedIn company ids, round 4 (2026-10-12), checked by hand by the user on LinkedIn: the companies added in V43 and
-- V44 (Apple, D. E. Shaw).
UPDATE companies c
SET config = c.config || jsonb_build_object('linkedinCompanyId', v.id)
FROM (VALUES
    ('apple', '162479'), ('deshaw', '6508'), ('arcesium', '9498084'), ('imc', '200030'), ('optiver', '13216'),
    ('alphagrep', '2282504'), ('nk-securities', '28820226'), ('observe-ai', '18090845'), ('agoda', '66719'),
    ('zendesk', '418095'), ('eightfold-ai', '28145130')
) AS v (slug, id)
WHERE c.slug = v.slug;
