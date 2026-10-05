package io.github.saksham023.jobagent.llm;

/**
 * A `claude -p` call failed: the CLI is missing, timed out, is not logged in, or did not answer with JSON.
 * The message is meant for a human ("claude -p failed: Failed to authenticate ...").
 */
public class ClaudeCliException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ClaudeCliException(String message) {
        super(message);
    }

    public ClaudeCliException(String message, Throwable cause) {
        super(message, cause);
    }
}