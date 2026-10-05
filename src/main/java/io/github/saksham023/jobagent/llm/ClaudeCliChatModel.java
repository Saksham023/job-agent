package io.github.saksham023.jobagent.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.StructuredOutputChatOptions;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * A Spring AI ChatModel answered by the Claude Code CLI in headless mode (`claude -p`), so the app can use
 * Claude on the user's subscription without an API key. Callers depend only on the ChatModel interface, so this
 * can be swapped for Spring AI's AnthropicChatModel or OllamaChatModel without changing them.
 *
 * One call = one process: the system messages become --system-prompt, the user messages are written to stdin,
 * an output schema (StructuredOutputChatOptions) becomes --json-schema, and the CLI's JSON reply is mapped back
 * to a ChatResponse. Tools, MCP servers and skills are switched off and the process runs in an empty folder, so
 * the model sees exactly the prompt and nothing else (no CLAUDE.md, no project settings). Single-turn only.
 */
@Component
public class ClaudeCliChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCliChatModel.class);

    /** Metadata key of the API-equivalent price of a call in US dollars (a Double). */
    public static final String COST_USD = "costUsd";

    private final ClaudeCliProperties properties;
    private final JsonMapper jsonMapper;

    public ClaudeCliChatModel(ClaudeCliProperties properties, JsonMapper jsonMapper) {
        this.properties = properties;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        for (Message message : prompt.getInstructions()) {
            if (message.getMessageType() != MessageType.SYSTEM && message.getMessageType() != MessageType.USER) {
                throw new UnsupportedOperationException("ClaudeCliChatModel is single-turn: system and user messages only");
            }
        }
        String model = modelOf(prompt.getOptions());
        String schema = prompt.getOptions() instanceof StructuredOutputChatOptions structured
                ? structured.getOutputSchema() : null;
        String system = join(prompt.getSystemMessages());
        String user = join(prompt.getUserMessages());

        long start = System.nanoTime();
        String stdout = run(command(model, system, schema), user);
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        return toResponse(stdout, model, millis);
    }

    /** The options used when a Prompt carries none: just the default model. */
    @Override
    public ChatOptions getOptions() {
        return ChatOptions.builder().model(properties.defaultModel()).build();
    }

    // ---------------------------------------------------------------- the process

    /** The full command line; package-private so a test can check the flags. */
    List<String> command(String model, String system, String schema) {
        List<String> command = new ArrayList<>(List.of(
                properties.command(), "-p",
                "--model", model,
                "--output-format", "json",
                "--tools", "",                       // no file, shell or web tools: answer from the prompt only
                "--strict-mcp-config",               // no MCP servers (none are passed)
                "--disable-slash-commands",          // no skills
                "--no-session-persistence"));        // do not save a session per call
        if (!system.isBlank()) {
            command.addAll(List.of("--system-prompt", system));
        }
        if (schema != null && !schema.isBlank()) {
            command.addAll(List.of("--json-schema", withoutDraftDeclaration(schema)));
        }
        return command;
    }

    /**
     * Spring AI's schemas declare `"$schema": ".../draft/2020-12/schema"`, which the CLI's validator does not
     * know ("no schema with key or ref"), so it rejects the whole schema. The body only uses basic keywords (type,
     * properties, enum, required) that every draft understands, so the declaration is simply dropped.
     */
    String withoutDraftDeclaration(String schema) {
        try {
            JsonNode node = jsonMapper.readTree(schema);
            if (node instanceof ObjectNode object && object.has("$schema")) {
                object.remove("$schema");
                return jsonMapper.writeValueAsString(object);
            }
        } catch (RuntimeException e) {
            // not JSON we can read: pass it on unchanged and let the CLI report the problem
        }
        return schema;
    }

    /** Runs the CLI, feeding `input` on stdin; returns stdout. Kills the process after the timeout. */
    private String run(List<String> command, String input) {
        try (ExecutorService io = Executors.newVirtualThreadPerTaskExecutor()) {
            Files.createDirectories(properties.workDirPath());
            Process process = new ProcessBuilder(command).directory(properties.workDirPath().toFile()).start();

            // write stdin and read both outputs at the same time, so a full pipe buffer can never block us
            Future<?> writer = io.submit(() -> write(process.getOutputStream(), input));
            Future<String> stdout = io.submit(() -> read(process.getInputStream()));
            Future<String> stderr = io.submit(() -> read(process.getErrorStream()));

            if (!process.waitFor(properties.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new ClaudeCliException("claude -p timed out after " + properties.timeout());
            }
            writer.get();
            String out = stdout.get();
            if (process.exitValue() != 0 && out.isBlank()) {
                throw new ClaudeCliException("claude -p exited with " + process.exitValue() + ": " + stderr.get().strip());
            }
            return out;
        } catch (IOException e) {
            throw new ClaudeCliException("Cannot run '" + properties.command() + "': " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ClaudeCliException("Interrupted while waiting for claude -p", e);
        } catch (ExecutionException e) {
            throw new ClaudeCliException("claude -p I/O failed: " + e.getCause().getMessage(), e.getCause());
        }
    }

    private static void write(OutputStream stdin, String input) {
        try (stdin) {
            stdin.write(input.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(InputStream stream) {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------------------------------------------------------------- the answer

    /**
     * `--output-format json` prints one JSON object: `result` holds the answer text (or the error message when
     * `is_error` is true) and, with --json-schema, `structured_output` holds the validated JSON answer. `usage`
     * has the token counts and `total_cost_usd` the API-equivalent price (on a subscription nothing is billed, but
     * it shows how much of the plan's usage a call takes). Prompt tokens include the cached ones: every CLI call
     * carries several thousand tokens of Claude Code's own context, read from the cache.
     */
    private ChatResponse toResponse(String stdout, String model, long millis) {
        JsonNode reply;
        try {
            reply = jsonMapper.readTree(stdout);
        } catch (RuntimeException e) {
            throw new ClaudeCliException("claude -p did not print JSON: " + abbreviate(stdout));
        }
        if (reply.path("is_error").asBoolean(false)) {
            throw new ClaudeCliException("claude -p failed: " + reply.path("result").asString(abbreviate(stdout)));
        }
        JsonNode structured = reply.path("structured_output");
        String text = structured.isObject() || structured.isArray()
                ? jsonMapper.writeValueAsString(structured)
                : reply.path("result").asString("");

        JsonNode usage = reply.path("usage");
        long cacheRead = usage.path("cache_read_input_tokens").asLong(0);
        long cacheWrite = usage.path("cache_creation_input_tokens").asLong(0);
        int promptTokens = (int) (usage.path("input_tokens").asLong(0) + cacheRead + cacheWrite);
        int completionTokens = usage.path("output_tokens").asInt(0);
        double costUsd = reply.path("total_cost_usd").asDouble(0);

        log.debug("claude -p --model {} answered in {} ms ({} prompt + {} completion tokens, ${})",
                model, millis, promptTokens, completionTokens, costUsd);
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .model(model)
                .usage(new DefaultUsage(promptTokens, completionTokens, promptTokens + completionTokens, null,
                        cacheRead, cacheWrite))
                .keyValue("durationMs", millis)
                .keyValue(COST_USD, costUsd)
                .build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))), metadata);
    }

    private String modelOf(ChatOptions options) {
        return options != null && options.getModel() != null && !options.getModel().isBlank()
                ? options.getModel() : properties.defaultModel();
    }

    private static String join(List<? extends Message> messages) {
        return messages.stream().map(Message::getText).collect(Collectors.joining("\n\n"));
    }

    private static String abbreviate(String text) {
        String flat = text.strip().replaceAll("\\s+", " ");
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "...";
    }
}