-- Job status per user (2026-10-12): a signed-in user marks a job Saved, Applied, Referral asked or Not interested.
-- A row exists only while a job has a status (clearing it deletes the row), so the table holds what people marked,
-- not every user x job pair. "Not interested" jobs are hidden from that user's search; the others show a chip, and
-- the search can show only jobs with chosen statuses.
CREATE TABLE user_job_status (
    user_id     BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    job_id      BIGINT      NOT NULL REFERENCES jobs (id) ON DELETE CASCADE,
    status      TEXT        NOT NULL CHECK (status IN ('SAVED', 'APPLIED', 'REFERRAL_ASKED', 'NOT_INTERESTED')),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, job_id)                    -- also the index the search's join uses
);
