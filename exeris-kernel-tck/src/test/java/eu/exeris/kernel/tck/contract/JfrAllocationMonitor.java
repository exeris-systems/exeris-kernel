/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.tck.contract;

import com.sun.management.ThreadMXBean;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reusable JFR Zero-Allocation monitor infrastructure for all subsystem TCKs.
 *
 * <h2>Purpose</h2>
 * <p>Provides a single, consistent E2E pipeline for measuring {@code eu.exeris.*}
 * heap allocations on any hot path. Every subsystem TCK (Memory, Transport,
 * Telemetry, Persistence, Security, Graph) delegates to this class instead of
 * duplicating JFR boilerplate.
 *
 * <h2>Three-Phase Protocol</h2>
 * <ol>
 *   <li><b>Bootstrap</b> — all SPI objects created <em>before</em> JFR starts.
 *       Enterprise tier builds its entire slab/pool infrastructure here.</li>
 *   <li><b>Warm-up</b> — a discarded recording flushes JIT, class-loading,
 *       and JFR-internal allocations.</li>
 *   <li><b>Steady-state</b> — the <em>only</em> window that counts. The
 *       monitor captures {@code jdk.ObjectAllocationInNewTLAB},
 *       {@code jdk.ObjectAllocationOutsideTLAB}, and
 *       {@code jdk.ObjectAllocationSample} (throttle disabled) events, filters
 *       by {@code objectClass.getName().startsWith("eu.exeris.")} and by the
 *       workload thread, AND records the exact per-thread allocated-bytes delta.</li>
 * </ol>
 *
 * <h2>Two Signals (issue #178)</h2>
 * <p>The JFR event stream tells you <em>what</em> allocated, but
 * {@code ObjectAllocationInNewTLAB} only fires on a TLAB refill — small steady
 * allocations inside an already-active TLAB emit no event, so a JFR-only
 * "zero" can be a false pass. {@code ObjectAllocationSample} mitigates this
 * (it samples across the heap; the throttle is disabled to capture every
 * sample point), but sampling is still probabilistic. The authoritative
 * absence-proof is therefore the {@code ThreadMXBean.getThreadAllocatedBytes}
 * delta: exact, TLAB-granularity-immune. {@link #assertZeroExerisAllocations}
 * asserts <em>both</em> (zero {@code eu.exeris.*} events <em>and</em> zero
 * allocated bytes); the bytes check is skipped only when the JVM cannot report
 * per-thread allocation.
 *
 * <h2>Filter Strategy: objectClass, not Stack Trace</h2>
 * <p>Stack-trace filtering is fragile under C2 inlining — an inlined frame
 * may fall below the JFR capture depth, producing false negatives. Filtering
 * by <em>allocated type</em> is invariant to inlining depth.
 *
 * @since 0.5.0
 */
public final class JfrAllocationMonitor {

    private static final String EXERIS_PACKAGE = "eu.exeris.";
    private static final DateTimeFormatter JFR_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private JfrAllocationMonitor() {
        // utility class — no instances
    }

    /**
     * The hot-path workload to execute during warm-up and steady-state phases.
     */
    @FunctionalInterface
    public interface HotPathWorkload {
        /**
         * Executes the workload for the given number of iterations.
         *
         * @param iterations number of iterations to run
         */
        void run(int iterations);
    }

    /**
     * Configuration record for a JFR zero-allocation test.
     *
     * @param subsystemName     human-readable name (e.g. "Memory", "Transport")
     * @param testClassName     simple class name of the calling test (for file naming)
     * @param warmupIterations  iterations for the warm-up (discarded) phase
     * @param hotPathIterations iterations for the steady-state (measured) phase
     */
    public record Config(
            String subsystemName,
            String testClassName,
            int warmupIterations,
            int hotPathIterations
    ) {
        public Config {
            if (warmupIterations < 0) {
                throw new IllegalArgumentException("warmupIterations must be >= 0");
            }
            if (hotPathIterations < 1) {
                throw new IllegalArgumentException("hotPathIterations must be >= 1");
            }
        }

        /**
         * Default config: 1 000 warmup, 10 000 steady-state.
         */
        public static Config ofDefaults(String subsystemName, String testClassName) {
            return new Config(subsystemName, testClassName, 1_000, 10_000);
        }

        /**
         * High-density config for E2E integrity: 1 000 warmup, 1 000 000 steady-state.
         */
        public static Config ofHighDensity(String subsystemName, String testClassName) {
            return new Config(subsystemName, testClassName, 1_000, 1_000_000);
        }
    }

    /** Sentinel for {@link Result#allocatedBytesDelta} when the JVM cannot report per-thread bytes. */
    public static final long ALLOCATED_BYTES_UNAVAILABLE = -1L;

    /**
     * Result of a JFR allocation measurement.
     *
     * @param exerisAllocations   all detected {@code eu.exeris.*} allocation events on the workload
     *                            thread (JFR-sampled — see the class Javadoc on residual sampling
     *                            limits)
     * @param allocatedBytesDelta total bytes allocated by the workload thread across the
     *                            steady-state {@code workload.run()}, from
     *                            {@code ThreadMXBean.getThreadAllocatedBytes} — a JFR-independent
     *                            cross-check immune to TLAB-refill granularity (issue #178).
     *                            {@link #ALLOCATED_BYTES_UNAVAILABLE} if the JVM does not support
     *                            per-thread allocation accounting.
     * @param hotPathIterations   steady-state iteration count, used to assert a per-iteration
     *                            allocation <em>rate</em> (one-time setup noise is tolerated)
     * @param recordingFile       path to the .jfr file (kept for JMC inspection)
     */
    public record Result(
            List<RecordedEvent> exerisAllocations,
            long allocatedBytesDelta,
            int hotPathIterations,
            Path recordingFile
    ) {
        /**
         * Returns a human-readable summary of allocated class names and counts, plus the
         * JFR-independent thread-allocated-bytes delta.
         */
        public String summary() {
            return summariseClasses(exerisAllocations)
                    + " | thread-allocated-bytes-delta=" + allocatedBytesDelta;
        }
    }

    // =========================================================================
    // Main API
    // =========================================================================

    /**
     * Runs the full three-phase JFR measurement protocol and returns the result.
     *
     * <p>The caller is responsible for creating all SPI objects (allocators, engines,
     * sinks) <em>before</em> calling this method — that is the Bootstrap phase.
     *
     * @param config   measurement configuration
     * @param workload the hot-path code to measure
     * @return measurement result containing all {@code eu.exeris.*} allocations
     * @throws IOException if JFR recording I/O fails
     */
    public static Result measure(Config config, HotPathWorkload workload) throws IOException {
        // ── WARM-UP (discarded) ──────────────────────────────────────────────
        if (config.warmupIterations() > 0) {
            Path warmupFile = Files.createTempFile("tck-jfr-warmup-" + config.subsystemName() + "-", ".jfr");
            try (Recording warmup = new Recording()) {
                enableAllocationEvents(warmup);
                warmup.setDestination(warmupFile);
                warmup.start();
                workload.run(config.warmupIterations());
                warmup.stop();
            }
            Files.deleteIfExists(warmupFile);
        }

        // ── STEADY-STATE RECORDING ───────────────────────────────────────────
        Path reportsDir = Path.of("target", "jfr-reports");
        Files.createDirectories(reportsDir);
        String timestamp = LocalDateTime.now().format(JFR_TS);
        String fileName = config.testClassName() + "-" + config.subsystemName() + "-" + timestamp + ".jfr";
        Path recordingFile = reportsDir.resolve(fileName);

        // The workload runs synchronously on THIS thread; scope the allocation accounting to it so a
        // concurrent/leaked background thread (e.g. a transport carrier reactor from a prior test
        // still draining) cannot contaminate the measurement with its own eu.exeris.* allocations.
        // JFR records all threads globally — we filter collected events by the workload thread id.
        long workloadThreadId = Thread.currentThread().threadId();

        // JFR-independent cross-check (issue #178): ThreadMXBean reports exact per-thread allocated
        // bytes, immune to the TLAB-refill granularity that makes the JFR event stream under-report
        // small steady allocations. Bracketed TIGHTLY around workload.run() (inside rec.start()/
        // rec.stop() would fold the recording's own ~hundreds-of-KB calling-thread allocations into
        // the delta). The workload is run exactly once here — the monitor's two-phase
        // (warm-up + steady) contract is preserved; a third replay would overflow workloads that
        // pre-size state to warm-up+steady iterations. Residual JFR-sampler noise on the thread is
        // absorbed by the per-iteration-rate assertion (see assertZeroExerisAllocations).
        ThreadMXBean threadMx = threadAllocationBean();
        // Per-thread allocation tracking is on by default on HotSpot; if a prior test disabled it,
        // enable it transiently and RESTORE afterward so we don't leave a sticky global JVM mutation.
        boolean restoreAllocTracking = threadMx != null && !threadMx.isThreadAllocatedMemoryEnabled();
        if (restoreAllocTracking) {
            threadMx.setThreadAllocatedMemoryEnabled(true);
        }
        long allocatedBytesDelta;

        try (Recording rec = new Recording()) {
            enableAllocationEvents(rec);
            rec.setDestination(recordingFile);
            rec.start();

            long allocBefore = threadAllocatedBytes(threadMx, workloadThreadId);
            workload.run(config.hotPathIterations());
            long allocAfter = threadAllocatedBytes(threadMx, workloadThreadId);
            allocatedBytesDelta = (allocBefore == ALLOCATED_BYTES_UNAVAILABLE
                    || allocAfter == ALLOCATED_BYTES_UNAVAILABLE)
                    ? ALLOCATED_BYTES_UNAVAILABLE
                    : allocAfter - allocBefore;

            rec.stop();
        } finally {
            if (restoreAllocTracking) {
                threadMx.setThreadAllocatedMemoryEnabled(false);
            }
        }

        List<RecordedEvent> exerisAllocs = collectExerisAllocations(recordingFile, workloadThreadId);
        return new Result(exerisAllocs, allocatedBytesDelta, config.hotPathIterations(), recordingFile);
    }

    private static ThreadMXBean threadAllocationBean() {
        return (ManagementFactory.getThreadMXBean() instanceof ThreadMXBean bean
                && bean.isThreadAllocatedMemorySupported()) ? bean : null;
    }

    private static long threadAllocatedBytes(ThreadMXBean bean, long threadId) {
        if (bean == null) {
            return ALLOCATED_BYTES_UNAVAILABLE;
        }
        long bytes = bean.getThreadAllocatedBytes(threadId);
        return bytes < 0 ? ALLOCATED_BYTES_UNAVAILABLE : bytes;
    }

    // =========================================================================
    // Assertion helpers — Enterprise vs Community
    // =========================================================================

    /**
     * Asserts the Enterprise zero-allocation contract: zero heap allocation in the steady-state
     * phase.
     *
     * <p>Two independent signals are checked (issue #178): the {@code eu.exeris.*} JFR allocation
     * events (attribute <em>what</em> allocated, but under-report small steady allocations that fit
     * inside an active TLAB), and the {@code ThreadMXBean} per-thread allocated-bytes delta (exact,
     * TLAB-granularity-immune — proves the absence). A truly zero-allocation hot path satisfies both.
     */
    public static void assertZeroExerisAllocations(Result result, String hotPathDescription) {
        assertThat(result.exerisAllocations())
                .as("Enterprise hot path (%s) must allocate zero eu.exeris.* heap objects. "
                                + "Bootstrap allocations are excluded — recording started after createXxx(). "
                                + "If this fails: check for autoboxing, String.format(), or new collection "
                                + "instances on the hot path.\nDetected classes: %s",
                        hotPathDescription, result.summary())
                .isEmpty();

        // JFR-independent proof (issue #178): a zero-allocation hot path has no per-iteration
        // allocation. We assert the thread-allocated-bytes delta stays below the iteration count —
        // i.e. an average < 1 byte/iteration. The smallest heap object is ~16 bytes, so any genuine
        // per-iteration allocation blows far past this bound, while a small fixed one-time cost
        // (lazy init / re-resolve in the steady phase that warm-up didn't cover) is tolerated. This
        // catches the small steady allocations the TLAB-refill-granular JFR events miss. Skipped
        // only when the JVM cannot report per-thread allocation (ALLOCATED_BYTES_UNAVAILABLE).
        if (result.allocatedBytesDelta() != ALLOCATED_BYTES_UNAVAILABLE) {
            assertThat(result.allocatedBytesDelta())
                    .as("Enterprise hot path (%s) must have no per-iteration allocation "
                                    + "(ThreadMXBean cross-check, immune to TLAB-refill granularity): "
                                    + "thread-allocated-bytes delta must stay below the iteration count "
                                    + "(avg < 1 byte/iteration). A delta at/above it with zero eu.exeris.* "
                                    + "JFR events means the hot path allocates per call (possibly JDK "
                                    + "temporaries) the event stream under-reported.\nDetected: %s",
                            hotPathDescription, result.summary())
                    .isLessThan(result.hotPathIterations());
        }
    }

    /**
     * Asserts the Community bounded-allocation contract: allocations are proportional
     * to iteration count and within a per-iteration budget.
     *
     * @param result                result from {@link #measure}
     * @param iterations            number of hot-path iterations
     * @param maxAllocsPerIteration maximum allowed {@code eu.exeris.*} allocs per iteration
     * @param hotPathDescription    human-readable description for assertion messages
     */
    public static void assertBoundedExerisAllocations(Result result, int iterations,
                                                      int maxAllocsPerIteration,
                                                      String hotPathDescription) {
        long maxAllowed = (long) iterations * maxAllocsPerIteration;
        assertThat(result.exerisAllocations())
                .as("Community hot path (%s) allocation count (%d) exceeded budget "
                                + "(%d = %d iters × %d). Indicates runaway object churn. "
                                + "Detected classes: %s",
                        hotPathDescription,
                        result.exerisAllocations().size(), maxAllowed,
                        iterations, maxAllocsPerIteration,
                        result.summary())
                .hasSizeLessThanOrEqualTo((int) maxAllowed);
    }

    // =========================================================================
    // JFR internals
    // =========================================================================

    private static void enableAllocationEvents(Recording rec) {
        // Allocation events have no duration, so withThreshold(...) was a no-op on all three
        // (issue #178). InNewTLAB/OutsideTLAB only fire on a TLAB refill / large allocation, so
        // small steady allocations inside an active TLAB emit no event — ObjectAllocationSample
        // (JEP 349) samples across the heap and catches those, but it is governed by `throttle`
        // (a rate), not a threshold. Disable the throttle to capture every sample point.
        rec.enable("jdk.ObjectAllocationInNewTLAB");
        rec.enable("jdk.ObjectAllocationOutsideTLAB");
        rec.enable("jdk.ObjectAllocationSample").with("throttle", "off");
    }

    /**
     * Reads a JFR recording and returns the {@code eu.exeris.*} allocation events attributed to the
     * workload thread.
     *
     * <p>Events from other threads (e.g. a leaked transport carrier reactor still draining from a
     * prior test) are excluded — the zero/bounded-allocation contract concerns the measured hot
     * path, which runs on {@code workloadThreadId}, not unrelated background activity that happens
     * to overlap the recording window.
     *
     * @param jfrFile          the recording to read
     * @param workloadThreadId {@link Thread#threadId()} of the thread that ran the workload
     */
    static List<RecordedEvent> collectExerisAllocations(Path jfrFile, long workloadThreadId)
            throws IOException {
        List<RecordedEvent> result = new ArrayList<>();
        try (RecordingFile jfr = new RecordingFile(jfrFile)) {
            while (jfr.hasMoreEvents()) {
                RecordedEvent e = jfr.readEvent();
                String type = e.getEventType().getName();
                if ("jdk.ObjectAllocationInNewTLAB".equals(type)
                        || "jdk.ObjectAllocationOutsideTLAB".equals(type)
                        || "jdk.ObjectAllocationSample".equals(type)) {
                    RecordedClass objectClass = e.getValue("objectClass");
                    RecordedThread eventThread = e.getThread();
                    if (objectClass != null
                            && objectClass.getName().startsWith(EXERIS_PACKAGE)
                            && eventThread != null
                            && eventThread.getJavaThreadId() == workloadThreadId) {
                        result.add(e);
                    }
                }
            }
        }
        return result;
    }

    /**
     * Produces a concise class-name → count summary for assertion messages.
     */
    static String summariseClasses(List<RecordedEvent> events) {
        if (events.isEmpty()) {
            return "(none)";
        }
        TreeMap<String, Long> counts = new TreeMap<>();
        for (RecordedEvent e : events) {
            RecordedClass cls = e.getValue("objectClass");
            if (cls != null) {
                counts.merge(cls.getName(), 1L, Long::sum);
            }
        }
        StringBuilder sb = new StringBuilder();
        counts.forEach((k, v) -> sb.append(k).append('×').append(v).append(' '));
        return sb.toString().trim();
    }
}
