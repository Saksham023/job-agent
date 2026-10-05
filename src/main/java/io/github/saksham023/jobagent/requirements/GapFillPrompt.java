package io.github.saksham023.jobagent.requirements;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The gap filler's system prompt, read from classify/gap-filler-prompt.md: the text after the first "---" line.
 * The version comes from the title ("prompt v1") and is stored with every fill, so a new version asks again.
 */
public record GapFillPrompt(String version, String instructions) {

    static final String RESOURCE = "classify/gap-filler-prompt.md";

    private static final Pattern VERSION = Pattern.compile("prompt\\s+(v\\d+)", Pattern.CASE_INSENSITIVE);

    public static GapFillPrompt load() {
        try (InputStream in = GapFillPrompt.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing resource " + RESOURCE);
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + RESOURCE, e);
        }
    }

    static GapFillPrompt parse(String text) {
        Matcher version = VERSION.matcher(text.lines().findFirst().orElse(""));
        int separator = text.indexOf("\n---\n");
        if (!version.find() || separator < 0) {
            throw new IllegalArgumentException("The prompt needs a title with 'prompt vN' and a '---' line before the instructions");
        }
        return new GapFillPrompt(version.group(1).toLowerCase(), text.substring(separator + 5).strip());
    }
}
