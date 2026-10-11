-- Job alerts (2026-10-11): a user opts in, saves filters, connects Telegram, and gets new matching jobs after every crawl round.
-- Flow: the crawl writes each NEW job's id to alert_job_queue (same transaction as the job); a poller takes queued jobs
-- (FOR UPDATE SKIP LOCKED), finds the users whose alert_prefs fit each job, writes alert_matches, deletes the queue rows;
-- a second poller sends each user's pending matches as one Telegram message. No LLM anywhere: plain filters.

-- One row per user: the alert's filters. Empty array / NULL = no filter on that field (same meaning as the website).
CREATE TABLE alert_prefs (
    user_id           BIGINT      PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    enabled           BOOLEAN     NOT NULL DEFAULT false,      -- opt-in
    families          TEXT[]      NOT NULL DEFAULT '{}',       -- job families (main or extra tag), any of them
    min_years         INTEGER,                                 -- experience range the user wants; NULL = open
    max_years         INTEGER,
    include_unstated  BOOLEAN     NOT NULL DEFAULT true,       -- jobs that state no years
    skills            TEXT[]      NOT NULL DEFAULT '{}',       -- the job must ask for ALL of them
    cities            TEXT[]      NOT NULL DEFAULT '{}',       -- any of them; 'Remote' = remote jobs
    companies         TEXT[]      NOT NULL DEFAULT '{}',       -- company slugs, any of them
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (min_years IS NULL OR max_years IS NULL OR min_years <= max_years)
);
-- The inverted indexes for "which users want this family / city / company" (only opted-in users are indexed).
CREATE INDEX alert_prefs_families_idx  ON alert_prefs USING GIN (families)  WHERE enabled;
CREATE INDEX alert_prefs_cities_idx    ON alert_prefs USING GIN (cities)    WHERE enabled;
CREATE INDEX alert_prefs_companies_idx ON alert_prefs USING GIN (companies) WHERE enabled;

-- The to-do list: jobs not matched yet. Written with the job itself; the gap fill re-queues a job it re-classifies.
CREATE TABLE alert_job_queue (
    job_id     BIGINT      PRIMARY KEY REFERENCES jobs (id) ON DELETE CASCADE,
    queued_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- What to send: one row per (user, job), so nobody is ever alerted twice about the same job.
CREATE TABLE alert_matches (
    user_id          BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    job_id           BIGINT      NOT NULL REFERENCES jobs (id) ON DELETE CASCADE,
    status           TEXT        NOT NULL DEFAULT 'PENDING'
                                 CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED', 'SKIPPED')),
    attempts         INTEGER     NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    claimed_at       TIMESTAMPTZ,                              -- when a sender took it (stuck SENDING rows are retried)
    matched_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at          TIMESTAMPTZ,
    last_error       TEXT,
    PRIMARY KEY (user_id, job_id)
);
CREATE INDEX alert_matches_pending_idx ON alert_matches (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX alert_matches_sending_idx ON alert_matches (claimed_at) WHERE status = 'SENDING';

-- Which Telegram chat belongs to which user (the bot can only message people who started a chat with it).
CREATE TABLE telegram_links (
    user_id    BIGINT      PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    chat_id    BIGINT      NOT NULL UNIQUE,
    active     BOOLEAN     NOT NULL DEFAULT true,               -- false after /stop or when the user blocked the bot
    linked_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One-time "connect Telegram" tokens (only their SHA-256 hash is stored), valid for a few minutes.
CREATE TABLE telegram_link_tokens (
    token_hash  TEXT        PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    expires_at  TIMESTAMPTZ NOT NULL
);

-- Where the bot last read its incoming messages (Telegram's getUpdates offset), so none is handled twice.
CREATE TABLE telegram_poll_state (
    id              INTEGER PRIMARY KEY CHECK (id = 1),
    next_update_id  BIGINT  NOT NULL DEFAULT 0
);
INSERT INTO telegram_poll_state (id, next_update_id) VALUES (1, 0);
