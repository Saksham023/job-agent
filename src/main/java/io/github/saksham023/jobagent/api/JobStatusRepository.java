package io.github.saksham023.jobagent.api;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * user_job_status: one row per job a user marked, holding its status, the hidden flag, or both. A row left with
 * neither (status cleared on a job that is not hidden, or a job shown again without a status) is deleted.
 */
@Repository
public class JobStatusRepository {

    private final JdbcClient jdbc;

    public JobStatusRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Sets or changes the status (hidden stays as it is); false when the job does not exist. */
    public boolean set(long userId, long jobId, JobStatus status) {
        if (!JobStatus.STATUSES.contains(status)) {
            throw new IllegalArgumentException("status must be one of SAVED, APPLIED, REFERRAL_ASKED (hide a job with /hidden)");
        }
        return jdbc.sql("""
                        INSERT INTO user_job_status (user_id, job_id, status, updated_at)
                        SELECT :userId, j.id, :status, now() FROM jobs j WHERE j.id = :jobId
                        ON CONFLICT (user_id, job_id) DO UPDATE SET status = EXCLUDED.status, updated_at = now()
                        """)
                .param("userId", userId)
                .param("jobId", jobId)
                .param("status", status.name())
                .update() > 0;
    }

    /** Clears the status; a hidden job stays hidden. */
    @Transactional
    public void clear(long userId, long jobId) {
        jdbc.sql("UPDATE user_job_status SET status = NULL, updated_at = now() WHERE user_id = :userId AND job_id = :jobId AND hidden")
                .param("userId", userId)
                .param("jobId", jobId)
                .update();
        deleteIfEmpty(userId, jobId, "NOT hidden");
    }

    /** Hides the job (its status stays); false when the job does not exist. */
    public boolean hide(long userId, long jobId) {
        return jdbc.sql("""
                        INSERT INTO user_job_status (user_id, job_id, hidden, updated_at)
                        SELECT :userId, j.id, true, now() FROM jobs j WHERE j.id = :jobId
                        ON CONFLICT (user_id, job_id) DO UPDATE SET hidden = true, updated_at = now()
                        """)
                .param("userId", userId)
                .param("jobId", jobId)
                .update() > 0;
    }

    /** Shows the job again; its status stays. */
    @Transactional
    public void unhide(long userId, long jobId) {
        jdbc.sql("UPDATE user_job_status SET hidden = false, updated_at = now() WHERE user_id = :userId AND job_id = :jobId AND status IS NOT NULL")
                .param("userId", userId)
                .param("jobId", jobId)
                .update();
        deleteIfEmpty(userId, jobId, "status IS NULL");
    }

    /** Deletes the row the change would leave with neither a status nor the hidden flag. */
    private void deleteIfEmpty(long userId, long jobId, String condition) {
        jdbc.sql("DELETE FROM user_job_status WHERE user_id = :userId AND job_id = :jobId AND " + condition)
                .param("userId", userId)
                .param("jobId", jobId)
                .update();
    }
}
