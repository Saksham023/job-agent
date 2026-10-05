package io.github.saksham023.jobagent.mcp;

import io.github.saksham023.jobagent.job.JobQueryRepository;
import io.github.saksham023.jobagent.job.JobQueryRepository.JobDetails;
import io.github.saksham023.jobagent.matching.MatchService;
import io.github.saksham023.jobagent.matching.MatchService.MatchResponse;
import io.github.saksham023.jobagent.matching.Profile;
import io.github.saksham023.jobagent.requirements.JobFamily;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * MCP tools for finding jobs. Each @McpToolParam becomes one property of the tool's input JSON schema, and its
 * description is what Claude reads to know how to fill it.
 */
@Component
public class JobTools {

    static final int DEFAULT_LIMIT = 10;
    static final int MAX_LIMIT = 25;

    /** Some postings are ~20,000 characters; tool output goes straight into Claude's context. */
    static final int MAX_DESCRIPTION_CHARS = 12_000;

    /** Dates from the user ("since Monday") mean Indian days. */
    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    /** get_job's answer: the stored job, with the description cut to MAX_DESCRIPTION_CHARS if needed. */
    public record JobView(JobDetails job, boolean descriptionTruncated) {
    }

    private final MatchService matchService;
    private final JobQueryRepository jobQueryRepository;

    public JobTools(MatchService matchService, JobQueryRepository jobQueryRepository) {
        this.matchService = matchService;
        this.jobQueryRepository = jobQueryRepository;
    }

    @McpTool(
            name = "match_jobs",
            description = """
                    Finds open jobs in India that fit a candidate and ranks them 0-100, with matched and missing \
                    skills and short reasons for each job. Fill the arguments from the candidate's resume \
                    (experience, skills, languages). Do NOT fill preferredLocations from the resume's address: \
                    first ask the candidate whether they have a location preference. Explain the top results \
                    using matchedRequired, missingRequired and reasons, and share each job's url. For "what is \
                    new since <date>" questions, pass postedSince; each job's postedAt tells how fresh it is. \
                    experienceWindow in the answer is the years range that was applied.""",
            annotations = @McpTool.McpAnnotations(title = "Match jobs", readOnlyHint = true,
                    destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public MatchResponse matchJobs(
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
            @McpToolParam(required = false, description = "Job families to search. A job matches when its family or "
                    + "one of its secondaryFamilies is listed (an \"SDE - AI Engineer\" is DATA_ML, also "
                    + "SOFTWARE_ENGINEERING). Default: all engineering families.")
            List<JobFamily> families,
            @McpToolParam(required = false, description = "How many ranked jobs to return, 1 to 25. Default 10.")
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
        int size = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(limit, 1), MAX_LIMIT);
        return matchService.match(profile, size, startOfDayInIndia(postedSince));
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
                    preferred skills, primary languages, job family and secondary families). Use it when the candidate wants details \
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
        JobDetails shortened = new JobDetails(job.jobId(), job.company(), job.title(), job.url(), job.department(),
                job.locations(), job.cities(), job.remote(), job.employmentType(), job.postedAt(),
                job.firstSeenAt(), job.open(), job.minYears(), job.maxYears(), job.yearsEvidence(), job.family(),
                job.secondaryFamilies(), job.specialization(), job.requiredSkills(), job.preferredSkills(), job.primaryLanguages(),
                description.substring(0, MAX_DESCRIPTION_CHARS));
        return new JobView(shortened, true);
    }
}