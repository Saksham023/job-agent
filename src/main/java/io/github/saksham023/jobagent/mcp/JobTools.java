package io.github.saksham023.jobagent.mcp;

import io.github.saksham023.jobagent.matching.MatchService;
import io.github.saksham023.jobagent.matching.MatchService.MatchResponse;
import io.github.saksham023.jobagent.matching.Profile;
import io.github.saksham023.jobagent.requirements.JobFamily;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MCP tools for finding jobs. Each @McpToolParam becomes one property of the tool's input JSON schema, and its
 * description is what Claude reads to know how to fill it.
 */
@Component
public class JobTools {

    static final int DEFAULT_LIMIT = 10;
    static final int MAX_LIMIT = 25;

    private final MatchService matchService;

    public JobTools(MatchService matchService) {
        this.matchService = matchService;
    }

    @McpTool(
            name = "match_jobs",
            description = """
                    Finds open jobs in India that fit a candidate and ranks them 0-100, with matched and missing \
                    skills and short reasons for each job. Fill the arguments from the candidate's resume \
                    (experience, skills, languages). Do NOT fill preferredLocations from the resume's address: \
                    first ask the candidate whether they have a location preference. Explain the top results \
                    using matchedRequired, missingRequired and reasons, and share each job's url.""",
            annotations = @McpTool.McpAnnotations(title = "Match jobs", readOnlyHint = true,
                    destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public MatchResponse matchJobs(
            @McpToolParam(required = false, description = "Total years of professional experience. Omit if unknown.")
            Integer yearsOfExperience,
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
            @McpToolParam(required = false, description = "Job families to search. Default: all engineering families.")
            List<JobFamily> families,
            @McpToolParam(required = false, description = "How many ranked jobs to return, 1 to 25. Default 10.")
            Integer limit) {

        if (yearsOfExperience != null && (yearsOfExperience < 0 || yearsOfExperience > 50)) {
            throw new IllegalArgumentException("yearsOfExperience must be between 0 and 50");
        }
        Profile profile = new Profile(yearsOfExperience, skills, primaryLanguages, preferredLocations,
                openToRemote, families);
        int size = limit == null ? DEFAULT_LIMIT : Math.min(Math.max(limit, 1), MAX_LIMIT);
        return matchService.match(profile, size);
    }
}