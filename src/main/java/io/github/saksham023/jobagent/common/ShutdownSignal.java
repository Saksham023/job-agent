package io.github.saksham023.jobagent.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lets long background work (crawls, gap fills) stop in an orderly way when the app is told to stop (a deploy, a
 * restart, SIGTERM).
 *
 * The app runs a work item inside track(); when shutdown starts, this bean first sets the stopping flag (the work
 * checks isStopping() between its steps and winds down: saves what it has, records its run), then waits until all
 * tracked work has finished, at most jobagent.shutdown.wait-seconds, and only then lets Spring close the database.
 * It stops FIRST among the lifecycle beans (highest phase), so the database is still open while the work winds down.
 *
 * Shortcomings: work that does not check the flag (the skill-learning run) is simply waited for up to the limit and
 * then cut off; a crash or a hard kill gets no orderly stop at all.
 */
@Component
public class ShutdownSignal implements SmartLifecycle {

    /** Part of the error text of an interrupted run; the 24-hour crawl rule ignores runs that carry it. */
    public static final String INTERRUPTED = "interrupted: app shutting down";

    private static final Logger log = LoggerFactory.getLogger(ShutdownSignal.class);

    /** A tracked piece of work; closing it tells the shutdown it may proceed. */
    public interface Activity extends AutoCloseable {
        @Override
        void close();
    }

    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicInteger active = new AtomicInteger();
    private final long maxWaitMillis;
    private volatile boolean running;

    @Autowired
    public ShutdownSignal(@Value("${jobagent.shutdown.wait-seconds:60}") int waitSeconds) {
        this.maxWaitMillis = Math.max(0, waitSeconds) * 1000L;
    }

    /** For tests and code that never needs a wait. */
    public ShutdownSignal() {
        this(60);
    }

    /** True once the app has been told to stop. */
    public boolean isStopping() {
        return stopping.get();
    }

    /** Marks the start of a piece of work the shutdown must wait for; close the result when it is done. */
    public Activity track() {
        active.incrementAndGet();
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (closed.compareAndSet(false, true)) {
                active.decrementAndGet();
            }
        };
    }

    public int activeCount() {
        return active.get();
    }

    @Override
    public void start() {
        running = true;
    }

    /** Raises the flag, then waits (up to the limit) for the tracked work to wind down. */
    @Override
    public void stop() {
        stopping.set(true);
        running = false;
        int busy = active.get();
        if (busy > 0) {
            log.info("Shutting down: waiting up to {} s for {} running task(s) to wind down", maxWaitMillis / 1000, busy);
        }
        long deadline = System.currentTimeMillis() + maxWaitMillis;
        while (active.get() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (active.get() > 0) {
            log.warn("Shutting down: {} task(s) did not finish within {} s", active.get(), maxWaitMillis / 1000);
        } else if (busy > 0) {
            log.info("Shutting down: the running tasks have wound down");
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** Stops before every other lifecycle bean, while the database and the web server are still up. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
