-- A family the rules only GUESSED: the title just says "engineer" / "engineering" / "architect" (the catch-all rule
-- in classify/title-fallback.csv) and nothing more specific decided. The family stays SOFTWARE_ENGINEERING so searches
-- work at once, but the Opus gap filler checks it (only for jobagent.gap-fill.families) and clears the flag.
ALTER TABLE job_requirements
    ADD COLUMN family_guessed BOOLEAN NOT NULL DEFAULT false;