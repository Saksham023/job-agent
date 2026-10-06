package io.github.saksham023.jobagent.crawl.adapter;

import io.github.saksham023.jobagent.crawl.RawJob;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Collects finished jobs and hands them to the crawl's sink in batches, so a long crawl saves as it goes (see
 * JobBoardAdapter.fetchJobs(company, sink)). Call flush() at the end and when the crawl is stopped.
 */
final class Batcher {

    /** Jobs per batch for adapters that do not have their own setting. */
    static final int DEFAULT_SIZE = 25;

    private final Consumer<List<RawJob>> sink;
    private final int size;
    private final List<RawJob> pending = new ArrayList<>();

    Batcher(Consumer<List<RawJob>> sink) {
        this(sink, DEFAULT_SIZE);
    }

    Batcher(Consumer<List<RawJob>> sink, int size) {
        this.sink = sink;
        this.size = size;
    }

    void add(RawJob job) {
        pending.add(job);
        if (pending.size() >= size) {
            flush();
        }
    }

    void flush() {
        if (!pending.isEmpty()) {
            sink.accept(List.copyOf(pending));
            pending.clear();
        }
    }
}
