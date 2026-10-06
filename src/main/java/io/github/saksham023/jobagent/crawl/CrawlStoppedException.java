package io.github.saksham023.jobagent.crawl;

import io.github.saksham023.jobagent.common.ShutdownSignal;

/**
 * Thrown by a long crawl that noticed the app is shutting down. Whatever it had finished has already been handed over
 * for saving, so the crawl service records it as a partial crawl (or an interrupted failure when nothing was saved).
 */
public class CrawlStoppedException extends RuntimeException {

    public CrawlStoppedException() {
        super(ShutdownSignal.INTERRUPTED);
    }
}
