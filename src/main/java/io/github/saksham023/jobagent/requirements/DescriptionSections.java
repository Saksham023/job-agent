package io.github.saksham023.jobagent.requirements;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Splits a plain-text job description (as HtmlToText produces it: one line per paragraph or bullet) into
 * lines labeled by the section they sit in, using the short heading lines between them.
 * INTRO = about the company, benefits, legal boilerplate; PREFERRED = nice-to-have; REQUIRED = everything else
 * (responsibilities, requirements, and text before the first heading).
 */
public final class DescriptionSections {

    public enum Kind { INTRO, REQUIRED, PREFERRED }

    public record Line(String text, Kind section) {}

    private static final Pattern PREFERRED_HEADING = Pattern.compile(
            "(?i).*(?:preferred|nice[- ]to[- ]have|good[- ]to[- ]have|bonus|desired|plus|stand out|additional qualifications).*");

    /** Indian job-post templates use these headings for the REQUIREMENTS, despite "preferred"/"desired". */
    private static final Pattern REQUIREMENT_PROFILE_HEADING = Pattern.compile(
            "(?i).*(?:candidate profile|desired profile|desired candidate|desired skills).*");

    private static final Pattern INTRO_HEADING = Pattern.compile(
            "(?i).*(?:about (?:us|the company|the team|the organi[sz]ation)|^about\\s+\\S+\\s*:?$|who we are|our (?:mission|values|vision|story|culture)"
                    + "|why join|benefits|perks|what we offer|equal opportunit|life at|our commitment|diversity|inclusion"
                    + "|additional information|accommodations?|export control|work personas|privacy|disclaimer).*");

    /** A "nice to have" marker inside one sentence. */
    private static final Pattern PREFERRED_CUE = Pattern.compile(
            "(?i)\\bpreferred\\b|nice[- ]to[- ]have|good[- ]to[- ]have|\\bbonus\\b|\\bdesirable\\b|\\ba plus\\b|\\bdesired\\b");

    private static final Pattern PARENTHESES = Pattern.compile("\\([^)]*\\)");
    /** "5+ years", "3-5 yrs", "two years": a line containing years is content, never a heading. */
    private static final Pattern YEARS = Pattern.compile(
            "(?i)\\b(?:\\d{1,2}(?:\\.\\d+)?|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|fifteen)"
                    + "\\s*\\+?\\s*(?:(?:-|\\u2013|\\u2014|to)\\s*\\d{1,2}\\s*)?\\+?\\s*(?:years?|yrs?)\\b");

    private DescriptionSections() {
    }

    /** The description's content lines (headings removed), each with its section. */
    public static List<Line> lines(String description) {
        List<Line> lines = new ArrayList<>();
        if (description == null) {
            return lines;
        }
        Kind section = Kind.REQUIRED;
        for (String raw : description.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (isHeading(line)) {
                section = kindOf(line);
                continue;
            }
            lines.add(new Line(line, section));
        }
        return lines;
    }

    /** A short line that is not a bullet and has no sentence punctuation: "Nice to have", "Requirements:". */
    public static boolean isHeading(String line) {
        return !line.isEmpty() && line.length() <= 60 && !line.startsWith("-")
                && !line.matches(".*[.;].*") && !YEARS.matcher(line).find();
    }

    /** True when the sentence around this position says "preferred", "nice to have", "a plus"... */
    public static boolean preferredInSentence(String line, int position) {
        return PREFERRED_CUE.matcher(PARENTHESES.matcher(sentenceAround(line, position)).replaceAll(" ")).find();
    }

    private static Kind kindOf(String heading) {
        if (INTRO_HEADING.matcher(heading).matches()) {
            return Kind.INTRO;
        }
        if (PREFERRED_HEADING.matcher(heading).matches() && !REQUIREMENT_PROFILE_HEADING.matcher(heading).matches()) {
            return Kind.PREFERRED;
        }
        return Kind.REQUIRED;
    }

    /** The sentence of the line that contains this position (sentences end at ". " or "; "). */
    static String sentenceAround(String line, int position) {
        int start = Math.max(line.lastIndexOf(". ", position), line.lastIndexOf("; ", position));
        int endDot = line.indexOf(". ", position);
        int endSemi = line.indexOf("; ", position);
        int end = endDot < 0 ? endSemi : (endSemi < 0 ? endDot : Math.min(endDot, endSemi));
        return line.substring(start < 0 ? 0 : start + 2, end < 0 ? line.length() : end);
    }
}