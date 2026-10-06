package io.github.saksham023.jobagent.common;

import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** The stop flag and the wait for running work, with real (short) waits. */
class ShutdownSignalTest {

    @Test
    void theFlagIsOffUntilTheAppStops() {
        ShutdownSignal signal = new ShutdownSignal(5);
        signal.start();
        assertThat(signal.isStopping()).isFalse();
        assertThat(signal.isRunning()).isTrue();

        signal.stop();

        assertThat(signal.isStopping()).isTrue();
        assertThat(signal.isRunning()).isFalse();
    }

    @Test
    void stopReturnsAtOnceWhenNothingIsRunning() {
        ShutdownSignal signal = new ShutdownSignal(30);
        long start = System.nanoTime();
        signal.stop();
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(1000);
    }

    @Test
    void stopWaitsUntilTrackedWorkIsFinished() {
        ShutdownSignal signal = new ShutdownSignal(10);
        ShutdownSignal.Activity work = signal.track();
        assertThat(signal.activeCount()).isEqualTo(1);
        Executors.newSingleThreadScheduledExecutor().schedule((Runnable) work::close, 500, TimeUnit.MILLISECONDS);
        long start = System.nanoTime();

        signal.stop();

        long millis = (System.nanoTime() - start) / 1_000_000;
        assertThat(millis).isBetween(400L, 3000L);                 // it waited for the work, not for the 10 s limit
        assertThat(signal.activeCount()).isZero();
    }

    @Test
    void stopGivesUpAfterTheLimitWhenWorkNeverFinishes() {
        ShutdownSignal signal = new ShutdownSignal(1);
        signal.track();                                            // never closed
        long start = System.nanoTime();

        signal.stop();

        assertThat((System.nanoTime() - start) / 1_000_000).isBetween(900L, 3000L);
        assertThat(signal.isStopping()).isTrue();
        assertThat(signal.activeCount()).isEqualTo(1);
    }

    @Test
    void closingAnActivityTwiceCountsOnce() {
        ShutdownSignal signal = new ShutdownSignal(1);
        ShutdownSignal.Activity work = signal.track();
        work.close();
        work.close();
        assertThat(signal.activeCount()).isZero();
    }

    @Test
    void theShutdownSignalStopsBeforeEveryOtherLifecycleBean() {
        assertThat(new ShutdownSignal().getPhase()).isEqualTo(Integer.MAX_VALUE);
    }
}
