package io.github.saksham023.jobagent.api;

import java.util.List;
import java.util.Locale;

/** What a signed-in user marked a job as (table user_job_status). No status = no row. */
public enum JobStatus {
    SAVED, APPLIED, REFERRAL_ASKED, NOT_INTERESTED;

    /** "applied" -> APPLIED; an unknown name is an error, not silently dropped. */
    public static JobStatus parse(String name) {
        try {
            return valueOf(name.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("status must be one of SAVED, APPLIED, REFERRAL_ASKED, NOT_INTERESTED");
        }
    }

    public static List<JobStatus> parse(List<String> names) {
        return names == null ? List.of() : names.stream().filter(n -> n != null && !n.isBlank()).map(JobStatus::parse)
                .distinct().toList();
    }
}
