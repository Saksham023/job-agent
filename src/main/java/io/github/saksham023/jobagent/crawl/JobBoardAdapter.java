package io.github.saksham023.jobagent.crawl;

import io.github.saksham023.jobagent.company.Company;

import java.util.List;

/**
 * One implementation per job-board platform (Greenhouse, Lever, Workday...).
 * An adapter only translates the platform's API into RawJobs: no India filtering rules, no city
 * normalization, no database access. Those live in the normalizer and the repository.
 */
public interface JobBoardAdapter {

    /**
     * The platform key this adapter handles. Must equal `companies.platform` for the companies it crawls,
     * e.g. "greenhouse".
     */
    String platform();

    /**
     * Fetches all current jobs from this company's board, following pagination.
     * May narrow to India on the server when the platform supports it (cheaper), but does not have to:
     * the normalizer always applies the India filter.
     *
     * @throws RuntimeException on HTTP or parse failures; the crawl service records the failure per company
     */
    List<RawJob> fetchJobs(Company company);
}