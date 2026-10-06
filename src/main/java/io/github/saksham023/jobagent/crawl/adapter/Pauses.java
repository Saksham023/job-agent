package io.github.saksham023.jobagent.crawl.adapter;

import io.github.saksham023.jobagent.crawl.CrawlStoppedException;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/** Waiting between requests in a way that notices the app shutting down (see ShutdownSignal). */
final class Pauses {

    private Pauses() {
    }

    /** Waits the given time, in slices of at most half a second; throws CrawlStoppedException as soon as stopping says so. */
    static void pause(Duration duration, BooleanSupplier stopping) {
        long end = System.nanoTime() + duration.toNanos();
        while (true) {
            checkStopping(stopping);
            long left = end - System.nanoTime();
            if (left <= 0) {
                return;
            }
            try {
                Thread.sleep(Math.min(Duration.ofNanos(left).toMillis() + 1, 500));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while crawling", e);
            }
        }
    }

    static void checkStopping(BooleanSupplier stopping) {
        if (stopping.getAsBoolean()) {
            throw new CrawlStoppedException();
        }
    }
}
