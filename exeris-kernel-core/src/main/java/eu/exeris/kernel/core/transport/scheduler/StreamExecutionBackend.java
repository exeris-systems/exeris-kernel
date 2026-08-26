/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.transport.scheduler;

/**
 * PAQS stream execution seam: the injection point at which an admitted stream's root task
 * is started.
 *
 * <p>The default backend used by {@link PaqsScheduler} spawns one Virtual Thread per stream
 * using {@link Thread#ofVirtual()}, preserving the VT root-per-stream guarantee. It is
 * behaviourally identical to the prior inline spawn and changes no admission, load-shed, or
 * JFR behaviour — extracting the spawn behind this seam is refactor-neutral.
 *
 * <p>The seam lets an alternative execution strategy be injected <em>without</em> touching the
 * scheduler's admission or load-shedding path. Two intended (non-default) consumers:
 * <ul>
 *   <li>a locality-affine backend that pins Virtual Thread continuations to a supplied carrier
 *       scheduler — a custom-scheduler experiment whose payoff regime is native io_uring ingress,
 *       not the stock JVM carrier pool;</li>
 *   <li>a deterministic {@code SimulationScheduler} that single-steps stream execution for
 *       reproducible simulation testing.</li>
 * </ul>
 *
 * <p>The interface is blind to which strategy is installed — locality-affinity vs deterministic
 * simulation is a backend choice, not an interface concern. Both rest on the same substrate: a
 * custom Virtual Thread carrier scheduler (the capability a research/Enterprise backend reaches
 * for at {@code Thread.ofVirtual()} construction), which is why one agnostic seam serves both.
 * Their <em>scope</em> differs, though: carrier affinity is transport-local, so this PAQS seam
 * suffices for the locality re-test; a full deterministic-simulation harness additionally needs
 * the same scheduler installed at the kernel's other Virtual-Thread spawn sites — a kernel-wide
 * promotion of this seam that is gated on the DST RFC (total-order vs placement).
 *
 * <p>Neither consumer ships on the default path; the default backend is the only wired
 * implementation. The seam is Core-internal (not SPI) — it carries no driver or native detail
 * across The Wall.
 *
 * @since 0.11.0
 */
@FunctionalInterface
public interface StreamExecutionBackend {

    /**
     * Starts stream execution with the given thread name and task.
     *
     * <p>The default implementation uses {@link Thread#ofVirtual()}, but custom backends may
     * execute the task inline, use carrier threads, or employ other strategies. The provided
     * {@code threadName} is for observability (visible in thread dumps and JFR); custom backends
     * may ignore or adapt it.
     *
     * <p>The {@code task} encapsulates all stream handling work, including {@link ScopedValue}
     * binding and exception isolation. Implementations must ensure the task runs with appropriate
     * concurrency semantics for the chosen backend.
     *
     * @param threadName the requested thread name for observability (format:
     *                   {@code paqs/<engineName>/<priority>/<streamId>}); must not be {@code null}
     * @param task       the stream execution task; must not be {@code null}
     */
    void start(String threadName, Runnable task);
}
