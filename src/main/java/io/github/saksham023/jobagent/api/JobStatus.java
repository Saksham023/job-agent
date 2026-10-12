package io.github.saksham023.jobagent.api;

import java.util.List;
import java.util.Locale;

/**
 * What a signed-in user marked a job as (table user_job_status). SAVED, APPLIED and REFERRAL_ASKED are the job's status
 * (at most one); HIDDEN is the separate hidden flag, which a job can have together with a status (Applied and hidden).
 * As a search filter, HIDDEN selects the hidden jobs.
 */
public enum JobStatus {
    SAVED, APPLIED, REFERRAL_ASKED, HIDDEN;

    /** The values a job's status column can hold (HIDDEN is a flag of its own). */
    public static final List<JobStatus> STATUSES = List.of(SAVED, APPLIED, REFERRAL_ASKED);

    /** "applied" -> APPLIED; "not_interested" (the old name, in saved links) -> HIDDEN; an unknown name is an error. */
    public static JobStatus parse(String name) {
        String key = name == null ? "" : name.strip().toUpperCase(Locale.ROOT);
        if (key.equals("NOT_INTERESTED")) {
            return HIDDEN;
        }
        try {
            return valueOf(key);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("status must be one of SAVED, APPLIED, REFERRAL_ASKED, HIDDEN");
        }
    }

    public static List<JobStatus> parse(List<String> names) {
        return names == null ? List.of() : names.stream().filter(n -> n != null && !n.isBlank()).map(JobStatus::parse)
                .distinct().toList();
    }
}
