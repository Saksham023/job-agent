-- A crawl that saved some of its jobs and then stopped (throttled too long, blocked, app restarted mid-way is NOT
-- recorded because nothing runs to record it) is recorded as PARTIAL. Like SUSPECT and FAILED it never closes jobs.
ALTER TABLE crawl_runs DROP CONSTRAINT crawl_runs_status_check;
ALTER TABLE crawl_runs ADD CONSTRAINT crawl_runs_status_check CHECK (status IN ('OK', 'SUSPECT', 'FAILED', 'PARTIAL'));
