package io.github.saksham023.jobagent.mcp;

import io.github.saksham023.jobagent.job.JobQueryRepository;
import io.github.saksham023.jobagent.job.JobQueryRepository.JobDetails;
import io.github.saksham023.jobagent.matching.Profile;
import io.github.saksham023.jobagent.requirements.JobFamily;
import io.github.saksham023.jobagent.search.SearchService;
import io.github.saksham023.jobagent.search.SearchService.ExportRow;
import io.github.saksham023.jobagent.search.SearchService.SearchPage;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;

/**
 * MCP tools for finding jobs. Each @McpToolParam becomes one property of the tool's input JSON schema, and its
 * description is what Claude reads to know how to fill it.
 */
@Component
public class JobTools {

    /** Some postings are ~20,000 characters; tool output goes straight into Claude's context. */
    static final int MAX_DESCRIPTION_CHARS = 12_000;

    /** Dates from the user ("since Monday") mean Indian days. */
    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    /** get_job's answer: the stored job, with the description cut to MAX_DESCRIPTION_CHARS if needed. */
    public record JobView(JobDetails job, boolean descriptionTruncated) {
    }

    private final SearchService searchService;
    private final JobQueryRepository jobQueryRepository;

    public JobTools(SearchService searchService, JobQueryRepository jobQueryRepository) {
        this.searchService = searchService;
        this.jobQueryRepository = jobQueryRepository;
    }

    @McpTool(
            name = "match_jobs",
            description = """
                    Searches open jobs in India for a candidate. Rules shortlist and rank the jobs, then a model \
                    judge reads each posting and keeps only jobs worth applying to: every returned job has a \
                    verdict (APPLY, or MAYBE once no APPLY are left) and a one-line reason. Fill the arguments from \
                    the candidate's resume (experience, skills, languages) and from what they said they want \
                    (wants). Do NOT fill preferredLocations from the resume's address: first ask the candidate \
                    whether they have a location preference. The first answer takes ~15-30 s. It returns a \
                    searchId: call more_jobs with it to get the next jobs (judging continues in the background), \
                    and export_jobs for a plain list of every APPLY job. Share each job's url. For "what is new \
                    since <date>" pass postedSince.""",
            annotations = @McpTool.McpAnnotations(title = "Match jobs", readOnlyHint = true,
                    destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public SearchPage matchJobs(
            @McpToolParam(required = false, description = "Total years of professional experience as on the resume, "
                    + "decimals allowed (e.g. 1.6 for Aug 2024 to Mar 2026); the server rounds them. Omit if unknown.")
            Double yearsOfExperience,
            @McpToolParam(description = "All technical skills from the resume as written: languages, frameworks, "
                    + "databases, cloud, tools. Example: [\"Java\", \"Spring Boot\", \"Kafka\", \"PostgreSQL\"].")
            List<String> skills,
            @McpToolParam(required = false, description = "The 1-3 programming languages the candidate mainly works "
                    + "in. Omit to derive them from skills.")
            List<String> primaryLanguages,
            @McpToolParam(required = false, description = "Cities or regions the candidate EXPLICITLY asked for, e.g. "
                    + "[\"Bengaluru\", \"NCR\"]. Never take them from the resume's address. Omit = anywhere in India.")
            List<String> preferredLocations,
            @McpToolParam(required = false, description = "Whether remote jobs are acceptable. Default true.")
            Boolean openToRemote,
            @McpToolParam(required = false, description = "Job families to search; ALWAYS pass the ones that match what "
                    + "the candidate wants, e.g. backend or full-stack -> [SOFTWARE_ENGINEERING]; AI engineering -> "
                    + "[SOFTWARE_ENGINEERING, DATA_ML]; DevOps/SRE -> [INFRA_DEVOPS]. A job matches when its family or "
                    + "one of its secondaryFamilies is listed. Omitting it searches every engineering family, including "
                    + "QA, security and engineering management, which mostly adds jobs the judge rejects.")
            List<JobFamily> families,
            @McpToolParam(required = false, description = "The kinds of roles the candidate wants, in their words, e.g. "
                    + "\"backend or AI engineering roles, no frontend\". Ask if unclear; omit if they did not say.")
            String wants,
            @McpToolParam(required = false, description = "How many jobs to return now, 1 to 25. Default 10.")
            Integer limit,
            @McpToolParam(required = false, description = "Only jobs posted on or after this date, YYYY-MM-DD "
                    + "(India time), e.g. for \"what is new since Monday\". Omit for all open jobs.")
            String postedSince,
            @McpToolParam(required = false, description = "ONLY if the candidate explicitly asks for a different "
                    + "experience range: the lowest years a job may ask for. By default jobs overlapping the "
                    + "candidate's years minus 2 to plus 1 are shown; do not set this otherwise.")
            Integer jobYearsFrom,
            @McpToolParam(required = false, description = "ONLY if the candidate explicitly asks for a different "
                    + "experience range: the highest years a job may ask for, e.g. 4 for \"also show jobs asking up to "
                    + "4 years\". Do not set this otherwise.")
            Integer jobYearsTo) {

        if (yearsOfExperience != null && (yearsOfExperience < 0 || yearsOfExperience > 50)) {
            throw new IllegalArgumentException("yearsOfExperience must be between 0 and 50");
        }
        for (Integer bound : new Integer[]{jobYearsFrom, jobYearsTo}) {
            if (bound != null && (bound < 0 || bound > 50)) {
                throw new IllegalArgumentException("jobYearsFrom and jobYearsTo must be between 0 and 50");
            }
        }
        Profile profile = new Profile(yearsOfExperience, skills, primaryLanguages, preferredLocations,
                openToRemote, families, jobYearsFrom, jobYearsTo);
        return searchService.start(profile, wants, startOfDayInIndia(postedSince), limit);
    }

    @McpTool(
            name = "more_jobs",
            description = """
                    Returns the next jobs of a search started with match_jobs: APPLY jobs first, MAYBE jobs only when \
                    no APPLY can come any more. If fewer than asked are ready, the note says how many are still \
                    being judged; ask again a little later.""",
            annotations = @McpTool.McpAnnotations(title = "More jobs", readOnlyHint = true,
                    destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public SearchPage moreJobs(
            @McpToolParam(description = "The searchId from match_jobs.") String searchId,
            @McpToolParam(required = false, description = "How many jobs, 1 to 25. Default 10.") Integer count) {
        return searchService.more(searchId(searchId), count);
    }

    @McpTool(
            name = "export_jobs",
            description = """
                    Every APPLY job of a search judged so far, as company, title and apply link, for a plain list \
                    the candidate can work through (includeMaybe adds the MAYBE ones).""",
            annotations = @McpTool.McpAnnotations(title = "Export jobs", readOnlyHint = true,
                    destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public List<ExportRow> exportJobs(
            @McpToolParam(description = "The searchId from match_jobs.") String searchId,
            @McpToolParam(required = false, description = "Also list MAYBE jobs. Default false.") Boolean includeMaybe) {
        return searchService.export(searchId(searchId), Boolean.TRUE.equals(includeMaybe));
    }

    static UUID searchId(String value) {
        try {
            return UUID.fromString(value.strip());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("searchId must be the id returned by match_jobs, got: " + value);
        }
    }

    /** "2026-10-01" -> midnight of that day in India; null or blank -> null (no date filter). */
    static Instant startOfDayInIndia(String date) {
        if (date == null || date.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(date.strip()).atStartOfDay(INDIA).toInstant();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("postedSince must be a date like 2026-10-01, got: " + date);
        }
    }

    @McpTool(
            name = "get_job",
            description = """
                    Returns one job in full: company, title, url, locations, the complete description (cut at \
                    12,000 characters), and what was extracted from it (years of experience, required and \
                    preferred skills, primary languages, job family and secondary families; filledByModel lists the \
                    fields a model filled where the rules found nothing). Use it when the candidate wants details \
                    about a job from match_jobs; pass that job's jobId.""",
            annotations = @McpTool.McpAnnotations(title = "Get job", readOnlyHint = true,
                    destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public JobView getJob(
            @McpToolParam(description = "The jobId of a job returned by match_jobs.")
            long jobId) {

        JobDetails job = jobQueryRepository.findDetails(jobId)
                .orElseThrow(() -> new IllegalArgumentException("No job with id " + jobId));
        String description = job.description();
        if (description == null || description.length() <= MAX_DESCRIPTION_CHARS) {
            return new JobView(job, false);
        }
        JobDetails shortened = job.withDescription(description.substring(0, MAX_DESCRIPTION_CHARS));
        return new JobView(shortened, true);
    }
}