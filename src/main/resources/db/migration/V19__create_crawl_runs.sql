-- Operations (2026-10-06): one row per crawl of one company, from the scheduler or by hand. It is the crawl history
-- for health checks, and it decides when a job is closed: a job that the last N successful (OK) crawls of its company
-- did not see is closed (N = jobagent.crawl.schedule.close-after-misses, 2). SUSPECT runs (the count collapsed) and
-- FAILED runs never close anything.
CREATE TABLE crawl_runs (
                            id              BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                            company_id      BIGINT      NOT NULL REFERENCES companies (id),
                            trigger         TEXT        NOT NULL CHECK (trigger IN ('schedule', 'manual')),
                            started_at      TIMESTAMPTZ NOT NULL,                    -- = last_seen_at of every job this crawl saw
                            finished_at     TIMESTAMPTZ NOT NULL,
                            status          TEXT        NOT NULL CHECK (status IN ('OK', 'SUSPECT', 'FAILED')),
                            fetched         INTEGER     NOT NULL DEFAULT 0,
                            kept            INTEGER     NOT NULL DEFAULT 0,          -- jobs in the wanted countries
                            inserted        INTEGER     NOT NULL DEFAULT 0,
                            updated         INTEGER     NOT NULL DEFAULT 0,
                            unchanged       INTEGER     NOT NULL DEFAULT 0,
                            closed          INTEGER     NOT NULL DEFAULT 0,
                            unresolved      INTEGER     NOT NULL DEFAULT 0,          -- jobs with no resolved country
                            no_description  INTEGER     NOT NULL DEFAULT 0,          -- kept jobs without a description
                            alerts          TEXT[]      NOT NULL DEFAULT '{}',
                            error           TEXT,
                            elapsed_ms      BIGINT      NOT NULL DEFAULT 0
);

CREATE INDEX crawl_runs_company_started_idx ON crawl_runs (company_id, started_at DESC);