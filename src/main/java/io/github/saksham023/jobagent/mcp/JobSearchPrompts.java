package io.github.saksham023.jobagent.mcp;

import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.springframework.ai.mcp.annotation.McpArg;
import org.springframework.ai.mcp.annotation.McpPrompt;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * MCP prompts: ready-made workflows the USER picks (in Claude Code they show up as slash commands). Unlike a
 * tool, a prompt runs no code against our data; it returns messages that are put into the conversation as if
 * the user had typed them, so Claude follows the same steps every time.
 */
@Component
public class JobSearchPrompts {

    private static final String WORKFLOW = """
            Help me find jobs in India that fit my resume, using the job-agent tools.

            1. Resume: %s
            2. From the resume, work out my total years of professional experience (internships count \
            as 0), my skills, and my primary programming languages (the ones I mainly build in, not every \
            language I have touched). Show me this profile in a few lines and let me correct it.
            3. Ask me where I want to work (cities or "anywhere in India") and whether remote roles are \
            fine. Do NOT take this from the address on my resume: where I live is not where I want to work.
            4. If my resume points to one area (for example backend), suggest the matching job families \
            and let me confirm.
            5. Call match_jobs with that profile.
            6. Group the results: strong fits, worth a look, and skip. For each job give the company, \
            title, location, score, matched and missing skills, and the url. Keep it short.
            7. Offer to open any job with get_job and compare its requirements with my resume line by line.

            Never apply to anything on my behalf.
            """;

    @McpPrompt(
            name = "find-jobs",
            title = "Find jobs for my resume",
            description = "Reads your resume, builds a search profile, asks where you want to work, then "
                    + "ranks matching jobs with match_jobs and explains the best fits.")
    public GetPromptResult findJobs(
            @McpArg(name = "resume", description = "Path to your resume file (PDF, DOCX, TXT). "
                    + "Leave empty and Claude will ask for it.", required = false) String resume) {

        String resumeStep = (resume == null || resume.isBlank())
                ? "ask me for my resume (a file path or pasted text) and read it."
                : "read my resume at " + resume.strip() + ".";

        PromptMessage message = new PromptMessage(Role.USER, TextContent.builder(WORKFLOW.formatted(resumeStep)).build());
        return GetPromptResult.builder(List.of(message))
                .description("Find jobs that fit a resume")
                .build();
    }
}