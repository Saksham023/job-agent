-- Jobs crawled from company job boards: one row per (company, platform job id).
-- Lifecycle: first_seen_at on first crawl, last_seen_at on every crawl that still returns the job,
-- closed_at when it disappears (set by change tracking in Milestone 7; cleared if it reappears).

CREATE TABLE jobs (
                      id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                      company_id         BIGINT      NOT NULL REFERENCES companies (id),
                      external_id        TEXT        NOT NULL,

                      title              TEXT        NOT NULL,
                      department         TEXT,
                      locations          TEXT[]      NOT NULL DEFAULT '{}',
                      cities             TEXT[]      NOT NULL DEFAULT '{}',
                      remote             BOOLEAN     NOT NULL DEFAULT FALSE,
                      employment_type    TEXT,
                      url                TEXT        NOT NULL,
                      description        TEXT,
                      posted_at          TIMESTAMPTZ,
                      source_updated_at  TIMESTAMPTZ,

                      content_hash       TEXT        NOT NULL,
                      raw                JSONB,

                      first_seen_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
                      last_seen_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
                      content_changed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                      closed_at          TIMESTAMPTZ,

                      CONSTRAINT jobs_company_external_uk UNIQUE (company_id, external_id),
                      CONSTRAINT jobs_seen_order_chk CHECK (last_seen_at >= first_seen_at)
);

-- "Open jobs, newest first" (new_jobs_since, digests).
CREATE INDEX jobs_open_first_seen_idx ON jobs (first_seen_at DESC) WHERE closed_at IS NULL;