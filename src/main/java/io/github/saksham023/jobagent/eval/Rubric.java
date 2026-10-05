package io.github.saksham023.jobagent.eval;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The judge's instructions, read from eval/judge-rubric.md: everything after the first "---" line is sent to the
 * model as the system prompt (the part above it explains the file to people). The version comes from the title
 * ("# Job-fit judge: rubric v2") and is stored with every judgment, so runs with different rubrics never mix.
 */
public record Rubric(String version, String instructions) {

    private static final Pattern VERSION = Pattern.compile("rubric\\s+(v\\d+)", Pattern.CASE_INSENSITIVE);

    public static Rubric load(Path file) {
        try {
            return parse(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the rubric " + file, e);
        }
    }

    static Rubric parse(String text) {
        String firstLine = text.lines().findFirst().orElse("");
        Matcher version = VERSION.matcher(firstLine);
        int separator = text.indexOf("\n---\n");
        if (!version.find() || separator < 0) {
            throw new IllegalArgumentException("The rubric needs a title with 'rubric vN' and a '---' line before the instructions");
        }
        return new Rubric(version.group(1).toLowerCase(), text.substring(separator + 5).strip());
    }
}