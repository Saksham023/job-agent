package io.github.saksham023.jobagent.alerts;

import io.github.saksham023.jobagent.alerts.AlertMatchRepository.MatchedJob;

import java.util.ArrayList;
import java.util.List;

/**
 * The Telegram message for one user's new matches (HTML: Telegram allows only a few tags, and &lt; &gt; &amp; must be
 * escaped). At most maxJobs are listed and the message stays under Telegram's 4,096-character limit; the rest are
 * counted as "and N more".
 */
public final class AlertMessage {

    static final int MAX_LENGTH = 3800;                  // under Telegram's 4,096, with room for the footer

    private AlertMessage() {
    }

    public static String build(List<MatchedJob> jobs, int maxJobs, String siteUrl) {
        String header = "<b>🔔 " + jobs.size() + (jobs.size() == 1 ? " new job" : " new jobs") + " for you</b>\n\n";
        StringBuilder body = new StringBuilder();
        int listed = 0;
        for (MatchedJob job : jobs) {
            String entry = entry(job);
            if (listed == maxJobs || header.length() + body.length() + entry.length() > MAX_LENGTH) {
                break;
            }
            body.append(entry);
            listed++;
        }
        StringBuilder message = new StringBuilder(header).append(body);
        int more = jobs.size() - listed;
        if (more > 0) {
            message.append("…and ").append(more).append(" more on JobRadar\n\n");
        }
        if (siteUrl != null && !siteUrl.isBlank()) {                 // each entry already ends with a blank line
            message.append("<a href=\"").append(escape(siteUrl)).append("\">Manage your alert</a> · /stop to pause");
        } else {
            message.append("/stop to pause these alerts");
        }
        return message.toString();
    }

    /** "• <a href=url>Title</a>\n   Company · Bengaluru, Pune · 2-4 yrs\n\n" */
    static String entry(MatchedJob job) {
        List<String> facts = new ArrayList<>();
        facts.add(escape(job.company()));
        String place = String.join(", ", job.cities());
        if (job.remote()) {
            place = place.isEmpty() ? "Remote" : place + " / Remote";
        }
        if (!place.isEmpty()) {
            facts.add(escape(place));
        }
        facts.add(years(job));
        return "• <a href=\"" + escape(job.url()) + "\">" + escape(job.title()) + "</a>\n   " + String.join(" · ", facts) + "\n\n";
    }

    /** As the website writes it: "2+ yrs", "2-4 yrs", "3 yrs", or "experience not stated". */
    static String years(MatchedJob job) {
        if (!job.yearsStated() || job.minYears() == null) {
            return "experience not stated";
        }
        if (job.maxYears() == null) {
            return job.minYears() + "+ yrs";
        }
        if (job.minYears().equals(job.maxYears())) {
            return job.minYears() + " yrs";
        }
        return job.minYears() + "-" + job.maxYears() + " yrs";
    }

    static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
