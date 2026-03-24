/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.transport.scheduler;

/**
 * PAQS stream execution seam for research variants.
 *
 * <p>The {@link StreamExecutionBackend} abstracts the mechanism by which admitted streams
 * are executed within the PAQS scheduler. The default implementation spawns one Virtual Thread
 * per stream using {@link Thread#ofVirtual()}, preserving the VT root-per-stream guarantee.
 *
 * <p>Custom implementations may inject alternative execution strategies for A/B research
 * without altering the scheduler's admission or load-shedding behavior.
 *
 * @since 0.5.1
 */
@FunctionalInterface
public interface StreamExecutionBackend {

    /**
     * Starts stream execution with the given thread name and task.
     *
     * <p>The default implementation uses {@link Thread#ofVirtual()}, but custom backends
     * may execute the task inline, use carrier threads, or employ other strategies
     * for research purposes. The provided {@code threadName} is for observability
     * (visible in thread dumps and JFR); custom backends may ignore or adapt it.
     *
     * <p>The {@code task} encapsulates all stream handling work, including ScopedValue
     * binding and exception isolation. Implementations must ensure the task runs
     * with appropriate concurrency semantics for the chosen backend.
     *
     * @param threadName the requested thread name for observability (format:
     *                   {@code paqs/<engineName>/<priority>/<streamId>}); must not be {@code null}
     * @param task       the stream execution task; must not be {@code null}
     */
    void start(String threadName, Runnable task);
}
