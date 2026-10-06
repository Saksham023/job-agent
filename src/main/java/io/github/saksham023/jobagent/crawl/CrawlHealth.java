package io.github.saksham023.jobagent.crawl;

import java.util.ArrayList;
import java.util.List;

/**
 * Judges one crawl against the company's history, so a broken source is noticed instead of silently emptying the
 * jobs table. Pure logic, no database.
 * - SUSPECT (saved, but nothing is closed): the count collapsed: 0 jobs while the last good crawl had some, or fewer
 *   than half of the usual count (median of the recent good crawls, when that is at least MIN_USUAL). Typical causes:
 *   the site moved to another platform, a filter id changed, the API changed shape.
 * - warnings (still OK): many locations not resolved, many jobs without a description.
 */
public final class CrawlHealth {

    public enum Status { OK, SUSPECT, FAILED }

    /** The verdict: a status and the alerts that explain it (empty when all is well). */
    public record Verdict(Status status, List<String> alerts) {
    }

    static final double DROP_RATIO = 0.5;
    static final int MIN_USUAL = 10;                     // below this, normal ups and downs are too large to judge
    static final double MAX_UNRESOLVED_SHARE = 0.10;
    static final double MAX_NO_DESCRIPTION_SHARE = 0.20;

    private CrawlHealth() {
    }

    /**
     * @param kept          jobs kept in the wanted countries
     * @param unresolved    jobs with no place resolved to any country
     * @param noDescription kept jobs without a description
     * @param recentOkKept  kept counts of the company's recent OK crawls, newest first (empty for a first crawl)
     */
    public static Verdict judge(int kept, int unresolved, int noDescription, List<Integer> recentOkKept) {
        List<String> alerts = new ArrayList<>();
        Status status = Status.OK;

        if (!recentOkKept.isEmpty()) {
            int last = recentOkKept.getFirst();
            int usual = median(recentOkKept);
            if (kept == 0 && last > 0) {
                status = Status.SUSPECT;
                alerts.add("no jobs found (last good crawl: " + last + "); jobs are not closed");
            } else if (usual >= MIN_USUAL && kept < usual * DROP_RATIO) {
                status = Status.SUSPECT;
                alerts.add("only " + kept + " jobs, usually about " + usual + "; jobs are not closed");
            }
        }
        int located = kept + unresolved;
        if (located > 0 && unresolved > located * MAX_UNRESOLVED_SHARE) {
            alerts.add(unresolved + " of " + located + " jobs have no resolved location (add aliases?)");
        }
        if (kept > 0 && noDescription > kept * MAX_NO_DESCRIPTION_SHARE) {
            alerts.add(noDescription + " of " + kept + " jobs have no description (detail requests failing?)");
        }
        return new Verdict(status, List.copyOf(alerts));
    }

    static int median(List<Integer> values) {
        List<Integer> sorted = values.stream().sorted().toList();
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(middle) : (sorted.get(middle - 1) + sorted.get(middle)) / 2;
    }
}