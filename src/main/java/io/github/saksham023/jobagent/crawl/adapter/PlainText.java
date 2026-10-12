package io.github.saksham023.jobagent.crawl.adapter;

import org.jsoup.nodes.Entities;

/**
 * Plain job text (lines, "* " or "- " bullets) as simple HTML, so the normalizer keeps its line structure: without
 * tags, HTML-to-text would join every line into one paragraph and the extractors would lose the bullets.
 */
final class PlainText {

    private PlainText() {
    }

    /** Each line a paragraph, bullet lines as list items; text that already has tags is returned as it is. */
    static String toHtml(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        if (text.matches("(?s).*<(p|ul|li|br|div)\\b.*")) {
            return text;
        }
        StringBuilder html = new StringBuilder();
        boolean inList = false;
        for (String line : text.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            boolean bullet = trimmed.matches("^[*\\-•●─]\\s+.*");
            if (bullet != inList) {
                html.append(bullet ? "<ul>" : "</ul>");
                inList = bullet;
            }
            String content = Entities.escape(bullet ? trimmed.substring(1).strip() : trimmed);
            html.append(bullet ? "<li>" : "<p>").append(content).append(bullet ? "</li>" : "</p>");
        }
        if (inList) {
            html.append("</ul>");
        }
        return html.toString();
    }
}
