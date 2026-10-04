-- A structured job function when the platform provides one (SmartRecruiters: "Engineering", "Human Resources").
-- Until now the requirements query read it from SmartRecruiters' raw JSON; adapters now fill this column.
ALTER TABLE jobs
    ADD COLUMN function TEXT;

-- Backfill the rows crawled before this column existed.
UPDATE jobs
SET function = raw -> 'function' ->> 'label'
WHERE raw -> 'function' ->> 'label' IS NOT NULL;
