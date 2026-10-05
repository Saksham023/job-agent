package io.github.saksham023.jobagent.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs ClaudeCliChatModel against small shell scripts that behave like `claude -p`: no login, no network.
 * Each script saves its arguments and stdin next to itself, so the tests can check what the model sent.
 */
class ClaudeCliChatModelTest {

    @TempDir
    Path dir;

    private ClaudeCliChatModel modelRunning(String scriptBody, Duration timeout) throws IOException {
        Path script = dir.resolve("fake-claude");
        Files.writeString(script, "#!/bin/bash\n"
                + "printf '%s\\n' \"$@\" > \"" + dir.resolve("args.txt") + "\"\n"
                + "cat > \"" + dir.resolve("stdin.txt") + "\"\n"
                + scriptBody + "\n");
        script.toFile().setExecutable(true);
        return new ClaudeCliChatModel(new ClaudeCliProperties(script.toString(), "opus", timeout,
                dir.resolve("work").toString()), JsonMapper.builder().build());
    }

    @Test
    void sendsSystemPromptSchemaAndModelAndReturnsTheStructuredAnswer() throws IOException {
        ClaudeCliChatModel model = modelRunning("""
                echo '{"is_error":false,"result":"","structured_output":{"answer":"YES"},"total_cost_usd":0.01,\
                "usage":{"input_tokens":10,"cache_read_input_tokens":6000,"cache_creation_input_tokens":500,"output_tokens":120}}'""",
                Duration.ofSeconds(10));
        Prompt prompt = new Prompt(List.of(new SystemMessage("You judge jobs."), new UserMessage("Is Java a language?")),
                StructuredOutputChatOptions.builder().model("haiku").outputSchema("{\"type\":\"object\"}").build());

        ChatResponse response = model.call(prompt);

        assertThat(response.getResult().getOutput().getText()).isEqualTo("{\"answer\":\"YES\"}");
        assertThat(response.getMetadata().getModel()).isEqualTo("haiku");
        assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(6510);       // cached tokens included
        assertThat(response.getMetadata().getUsage().getCompletionTokens()).isEqualTo(120);
        assertThat((Double) response.getMetadata().get(ClaudeCliChatModel.COST_USD)).isEqualTo(0.01);
        List<String> args = Files.readAllLines(dir.resolve("args.txt"));
        assertThat(args).containsSubsequence("-p", "--model", "haiku", "--output-format", "json");
        assertThat(args).containsSubsequence("--system-prompt", "You judge jobs.");
        assertThat(args).containsSubsequence("--json-schema", "{\"type\":\"object\"}");
        assertThat(Files.readString(dir.resolve("stdin.txt"))).isEqualTo("Is Java a language?");
    }

    @Test
    void usesTheDefaultModelAndThePlainResultWithoutASchema() throws IOException {
        ClaudeCliChatModel model = modelRunning("echo '{\"is_error\":false,\"result\":\"Yes, it is.\"}'", Duration.ofSeconds(10));

        assertThat(model.call(new Prompt("Is Java a language?")).getResult().getOutput().getText()).isEqualTo("Yes, it is.");
        assertThat(Files.readAllLines(dir.resolve("args.txt"))).containsSubsequence("--model", "opus")
                .doesNotContain("--json-schema", "--system-prompt");
    }

    @Test
    void anErrorReplyBecomesAnExceptionWithTheCliMessage() throws IOException {
        ClaudeCliChatModel model = modelRunning("""
                echo '{"is_error":true,"result":"Failed to authenticate: OAuth session expired"}'""", Duration.ofSeconds(10));

        assertThatThrownBy(() -> model.call(new Prompt("hi")))
                .isInstanceOf(ClaudeCliException.class)
                .hasMessageContaining("Failed to authenticate: OAuth session expired");
    }

    @Test
    void aHangingCallIsKilledAfterTheTimeout() throws IOException {
        ClaudeCliChatModel model = modelRunning("sleep 10", Duration.ofMillis(300));

        assertThatThrownBy(() -> model.call(new Prompt("hi")))
                .isInstanceOf(ClaudeCliException.class)
                .hasMessageContaining("timed out");
    }

    @Test
    void aMissingExecutableIsReportedClearly() {
        ClaudeCliChatModel model = new ClaudeCliChatModel(new ClaudeCliProperties(dir.resolve("nope").toString(),
                "opus", Duration.ofSeconds(5), dir.toString()), JsonMapper.builder().build());

        assertThatThrownBy(() -> model.call(new Prompt("hi")))
                .isInstanceOf(ClaudeCliException.class)
                .hasMessageContaining("Cannot run");
    }

    @Test
    void conversationsWithEarlierAnswersAreRejected() throws IOException {
        ClaudeCliChatModel model = modelRunning("echo '{}'", Duration.ofSeconds(10));

        assertThatThrownBy(() -> model.call(new Prompt(List.of(new UserMessage("hi"), new AssistantMessage("hello")))))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void theDraftDeclarationTheCliCannotReadIsRemovedFromTheSchema() throws IOException {
        ClaudeCliChatModel model = modelRunning("echo '{\"is_error\":false,\"result\":\"ok\"}'", Duration.ofSeconds(10));
        String springAiSchema = """
                {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",\
                "properties":{"verdict":{"type":"string","enum":["APPLY","NO"]}}}""";

        model.call(new Prompt(List.of(new UserMessage("hi")),
                StructuredOutputChatOptions.builder().outputSchema(springAiSchema).build()));

        List<String> args = Files.readAllLines(dir.resolve("args.txt"));
        String sent = args.get(args.indexOf("--json-schema") + 1);
        assertThat(sent).doesNotContain("$schema").contains("\"enum\":[\"APPLY\",\"NO\"]");
    }
}
