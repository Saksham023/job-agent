package io.github.saksham023.jobagent.referral;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The referral message is a template the browser fills for each job; no AI writes it. Placeholders are written
 * {{name}}; a part in [square brackets] is optional and left out when a placeholder inside it has no value (no resume
 * link, no years...). This class holds the default text and checks a user's own version.
 */
public final class ReferralTemplate {

    public static final String DEFAULT = """
            Hi, hope you're doing well!

            I'm applying for the {{jobTitle}} role at {{company}}:
            {{jobLink}}

            I'm {{headline}}[ with {{experience}}]. If you're comfortable, a referral would mean a lot.[

            Resume: {{resumeLink}}]""";

    /** Every placeholder the browser knows how to fill. */
    public static final List<String> PLACEHOLDERS = List.of(
            "company", "jobTitle", "jobLink", "resumeLink", "headline", "experience", "build", "years", "skills");

    static final int MAX_LENGTH = 600;

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z]+)\\s*}}");

    private ReferralTemplate() {
    }

    /** The user's text, tidied ("{{ company }}" -> "{{company}}"), or IllegalArgumentException with a message for them. */
    public static String check(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("The message can't be empty.");
        }
        String tidy = text.replace("\r\n", "\n").strip();
        if (tidy.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("The message is too long: at most " + MAX_LENGTH + " characters.");
        }
        StringBuilder out = new StringBuilder();
        Matcher m = PLACEHOLDER.matcher(tidy);
        while (m.find()) {
            String name = m.group(1);
            if (!PLACEHOLDERS.contains(name)) {
                throw new IllegalArgumentException("Unknown placeholder {{" + name + "}}. You can use: "
                        + String.join(", ", PLACEHOLDERS.stream().map(p -> "{{" + p + "}}").toList()) + ".");
            }
            m.appendReplacement(out, Matcher.quoteReplacement("{{" + name + "}}"));
        }
        m.appendTail(out);
        String result = out.toString();
        String outside = PLACEHOLDER.matcher(result).replaceAll("");
        if (outside.contains("{{") || outside.contains("}}")) {
            throw new IllegalArgumentException("A placeholder is not written right: use {{name}}, e.g. {{company}}.");
        }
        int depth = 0;
        for (char c : outside.toCharArray()) {
            if (c == '[' && ++depth > 1 || c == ']' && --depth < 0) {
                throw new IllegalArgumentException("Square brackets mark an optional part: open and close each one, and don't put one inside another.");
            }
        }
        if (depth != 0) {
            throw new IllegalArgumentException("Square brackets mark an optional part: open and close each one, and don't put one inside another.");
        }
        return result;
    }
}
