package io.github.saksham023.jobagent.crawl;

import io.github.saksham023.jobagent.company.Company;

import java.util.List;
import java.util.function.Consumer;

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

    /**
     * Like {@link #fetchJobs(Company)}, for adapters whose crawl is long (one request per job on a site that throttles):
     * they pass finished jobs to the sink in batches while they go, so the crawl service can save them at once, and
     * return only the jobs NOT yet passed (usually none). If the crawl then fails, what was passed is already saved.
     * The default passes nothing and returns everything, which is right for every adapter that is quick.
     */
    default List<RawJob> fetchJobs(Company company, Consumer<List<RawJob>> sink) {
        return fetchJobs(company);
    }

    /**
     * The server this company's crawl talks to. Companies on the same server are crawled one after another (never
     * two request streams from us to one server at once); different servers are crawled in parallel. Default: the
     * platform, right for platforms whose one API serves every company (api.lever.co, boards-api.greenhouse.io...).
     */
    default String serverKey(Company company) {
        return platform();
    }
}