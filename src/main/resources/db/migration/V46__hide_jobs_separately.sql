-- Hiding a job is separate from its status (2026-10-12, user's request): "Not interested" becomes "Hidden", and a job
-- can be Applied (or Saved, Referral asked) AND hidden at the same time, so hiding an applied job keeps the Applied mark.
-- A row now holds a status, the hidden flag, or both; a row with neither is deleted by the application.
ALTER TABLE user_job_status ADD COLUMN hidden BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE user_job_status ALTER COLUMN status DROP NOT NULL;

UPDATE user_job_status SET hidden = true, status = NULL WHERE status = 'NOT_INTERESTED';

ALTER TABLE user_job_status DROP CONSTRAINT user_job_status_status_check;
ALTER TABLE user_job_status ADD CONSTRAINT user_job_status_status_check
    CHECK (status IS NULL OR status IN ('SAVED', 'APPLIED', 'REFERRAL_ASKED'));
ALTER TABLE user_job_status ADD CONSTRAINT user_job_status_marked_check CHECK (status IS NOT NULL OR hidden);
