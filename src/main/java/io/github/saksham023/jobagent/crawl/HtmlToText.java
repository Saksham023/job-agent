package io.github.saksham023.jobagent.crawl;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Converts job-description HTML into readable plain text that keeps its structure:
 * paragraphs and headings on their own lines, list items as "- " lines.
 * Handles HTML that arrives escaped (Greenhouse sends "&lt;p&gt;") as well as normal HTML.
 */
public final class HtmlToText {

    /** Tags that start and end on their own line. */
    private static final Set<String> BLOCK_TAGS = Set.of(
            "p", "div", "section", "article", "header", "footer", "ul", "ol", "table", "tr",
            "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre", "hr");

    private static final Pattern HORIZONTAL_SPACE = Pattern.compile("[ \\t\\u00A0]+");      // incl. &nbsp;
    private static final Pattern SPACE_AROUND_NEWLINE = Pattern.compile(" *\\n *");
    private static final Pattern EMPTY_BULLET_LINE = Pattern.compile("\\n-\\n");           // <li><p>x</p></li>
    private static final Pattern BLANK_LINE_BEFORE_BULLET = Pattern.compile("\\n\\n+- ");
    private static final Pattern THREE_OR_MORE_NEWLINES = Pattern.compile("\\n{3,}");

    private HtmlToText() {
    }

    /** Returns plain text, or null when the input is null, blank, or has no visible text. */
    public static String convert(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }
        String source = looksEscaped(html) ? Parser.unescapeEntities(html, false) : html;

        TextCollector collector = new TextCollector();
        NodeTraversor.traverse(collector, Jsoup.parse(source).body());

        String text = tidy(collector.out.toString());
        return text.isEmpty() ? null : text;
    }

    /** "&lt;div&gt;..." with no real tags means the HTML itself was escaped once more. */
    static boolean looksEscaped(String html) {
        return html.contains("&lt;") && !html.contains("<");
    }

    private static String tidy(String text) {
        String result = HORIZONTAL_SPACE.matcher(text).replaceAll(" ");
        result = SPACE_AROUND_NEWLINE.matcher(result).replaceAll("\n");
        result = EMPTY_BULLET_LINE.matcher(result).replaceAll("\n- ");
        result = BLANK_LINE_BEFORE_BULLET.matcher(result).replaceAll("\n- ");
        result = THREE_OR_MORE_NEWLINES.matcher(result).replaceAll("\n\n");
        return result.strip();
    }

    /** Walks the HTML tree once, writing text and line breaks into a buffer. */
    private static final class TextCollector implements NodeVisitor {

        private final StringBuilder out = new StringBuilder();

        @Override
        public void head(Node node, int depth) {                    // entering a node
            if (node instanceof TextNode text) {
                out.append(text.text());
            } else if (node instanceof Element element) {
                String tag = element.normalName();
                if (tag.equals("br")) {
                    out.append('\n');
                } else if (tag.equals("li")) {
                    out.append("\n- ");
                } else if (BLOCK_TAGS.contains(tag)) {
                    out.append('\n');
                }
            }
        }

        @Override
        public void tail(Node node, int depth) {                    // leaving a node
            if (node instanceof Element element && BLOCK_TAGS.contains(element.normalName())) {
                out.append('\n');
            }
        }
    }
}