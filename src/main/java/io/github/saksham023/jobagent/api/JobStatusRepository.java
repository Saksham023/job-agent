package io.github.saksham023.jobagent.api;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** user_job_status: one row per job a user marked; clearing the status deletes the row. */
@Repository
public class JobStatusRepository {

    private final JdbcClient jdbc;

    public JobStatusRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Sets or changes the status; false when the job does not exist. */
    public boolean set(long userId, long jobId, JobStatus status) {
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

    public void clear(long userId, long jobId) {
        jdbc.sql("DELETE FROM user_job_status WHERE user_id = :userId AND job_id = :jobId")
                .param("userId", userId)
                .param("jobId", jobId)
                .update();
    }
}
