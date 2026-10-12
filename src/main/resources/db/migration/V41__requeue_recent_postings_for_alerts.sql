-- Job alerts used to queue only jobs seen for the first time, so a job a company RE-POSTED (an older job whose posting
-- date moved forward) was never alerted, although the website shows it as new. From now on a re-post is queued by the
-- crawl; this queues once the open jobs posted in the last two days, so re-posts missed before are matched now. Jobs
-- already sent are skipped (one alert per user and job), and the matcher still checks every filter and the post age.
INSERT INTO alert_job_queue (job_id)
SELECT id FROM jobs
WHERE closed_at IS NULL AND posted_at > now() - interval '2 days'
ON CONFLICT DO NOTHING;
