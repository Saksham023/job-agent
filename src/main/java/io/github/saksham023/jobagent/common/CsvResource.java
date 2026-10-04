package io.github.saksham023.jobagent.common;

import org.springframework.core.io.ClassPathResource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reads the small comma-separated data files under src/main/resources (geo/*.csv, classify/*.csv).
 * Blank lines and lines starting with # are skipped. Every row must have exactly `columns` fields; the LAST
 * field keeps any further commas, so a regex or an alias list may contain them. Fields are trimmed, and
 * empty trailing fields are kept ("IN,Karnataka,Bengaluru,,100," has 6 fields).
 */
public final class CsvResource {

    private CsvResource() {
    }

    /**
     * @param path    classpath location, e.g. "geo/cities.csv"
     * @param columns the exact number of fields per row
     * @throws IllegalStateException with file:line when a row has fewer fields, so a broken data file fails at startup
     */
    public static List<String[]> read(String path, int columns) {
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                String[] fields = trimmed.split(",", columns);
                if (fields.length != columns) {
                    throw new IllegalStateException(path + ":" + lineNumber + " expected " + columns
                            + " comma-separated fields but found " + fields.length + ": " + line);
                }
                rows.add(Arrays.stream(fields).map(String::strip).toArray(String[]::new));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + path, e);
        }
        return rows;
    }
}
