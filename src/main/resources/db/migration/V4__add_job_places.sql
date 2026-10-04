-- Parsed locations per job:
--   country_codes: distinct ISO alpha-2 codes of resolved places, used by the country filter ("IN")
--   places:        the full parsed list (city, region, country, metro, remote, status, raw) for display and debugging
ALTER TABLE jobs
    ADD COLUMN country_codes TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN places        JSONB  NOT NULL DEFAULT '[]'::jsonb,
    ADD CONSTRAINT jobs_places_is_array_chk CHECK (jsonb_typeof(places) = 'array');

-- Array searches. Use the operators GIN understands:
--   cities && ARRAY['Noida','Gurugram']   (overlaps: any of these cities)
--   country_codes @> ARRAY['IN']          (contains)
-- Note: 'IN' = ANY(country_codes) gives the same answer but can NOT use these indexes.
CREATE INDEX jobs_cities_gin_idx        ON jobs USING GIN (cities);
CREATE INDEX jobs_country_codes_gin_idx ON jobs USING GIN (country_codes);