package io.github.saksham023.jobagent.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Settings for ClaudeCliChatModel (jobagent.llm.claude-cli.*).
 *
 * @param command      the Claude Code executable; a full path when the app's PATH does not contain it
 * @param defaultModel the model when a request names none ("opus", "sonnet", "haiku" or a full model id)
 * @param timeout      how long one call may take before the process is killed
 * @param workDir      where the CLI runs; empty = a folder under the system temp dir. It must hold no CLAUDE.md
 *                     or .claude settings: the CLI reads those from its working directory
 */
@ConfigurationProperties("jobagent.llm.claude-cli")
public record ClaudeCliProperties(
        @DefaultValue("claude") String command,
        @DefaultValue("opus") String defaultModel,
        @DefaultValue("5m") Duration timeout,
        @DefaultValue("") String workDir) {

    public Path workDirPath() {
        return workDir.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"), "job-agent-claude-cli")
                : Path.of(workDir);
    }
}