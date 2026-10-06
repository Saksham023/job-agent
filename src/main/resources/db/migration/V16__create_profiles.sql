-- Milestone 8a: saved profiles. A profile is the candidate's facts as read from ONE resume (never the file itself,
-- no name, email or address). It is never edited: a new resume gives a new profile and a new id. Searches with the
-- same profile id use the same facts, so they get the same profile_hash and reuse every stored Opus verdict.
CREATE TABLE profiles (
                          id             TEXT        PRIMARY KEY,               -- "p-" + 8 random base32 characters
                          facts          JSONB       NOT NULL,                  -- years, skills, primary languages, wants
                          source         TEXT        NOT NULL,                  -- where it came from: mcp / api
                          defaults       JSONB,                                 -- preferences of the last search
                          profile_hash   TEXT,                                  -- judgments key of the last search
                          created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
                          last_used_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
                          last_search_at TIMESTAMPTZ
);

CREATE INDEX profiles_hash_idx ON profiles (profile_hash);

-- Which profile a search was for (null for searches made before profiles existed).
ALTER TABLE searches ADD COLUMN profile_id TEXT REFERENCES profiles (id);
CREATE INDEX searches_profile_idx ON searches (profile_id);
