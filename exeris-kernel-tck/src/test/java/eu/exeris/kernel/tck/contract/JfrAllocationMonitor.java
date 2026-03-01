/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.tck.contract;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
 *       {@code jdk.ObjectAllocationSample} events, then filters by
 *       {@code objectClass.getName().startsWith("eu.exeris.")}.</li>
 * </ol>
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

        /** Default config: 1 000 warmup, 10 000 steady-state. */
        public static Config ofDefaults(String subsystemName, String testClassName) {
            return new Config(subsystemName, testClassName, 1_000, 10_000);
        }

        /** High-density config for E2E integrity: 100 warmup, 1 000 000 steady-state. */
        public static Config ofHighDensity(String subsystemName, String testClassName) {
            return new Config(subsystemName, testClassName, 1_000, 1_000_000);
        }
    }

    /**
     * Result of a JFR allocation measurement.
     *
     * @param exerisAllocations all detected {@code eu.exeris.*} allocation events
     * @param recordingFile     path to the .jfr file (kept for JMC inspection)
     */
    public record Result(
            List<RecordedEvent> exerisAllocations,
            Path recordingFile
    ) {
        /** Returns a human-readable summary of allocated class names and counts. */
        public String summary() {
            return summariseClasses(exerisAllocations);
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

        try (Recording rec = new Recording()) {
            enableAllocationEvents(rec);
            rec.setDestination(recordingFile);
            rec.start();

            workload.run(config.hotPathIterations());

            rec.stop();
        }

        List<RecordedEvent> exerisAllocs = collectExerisAllocations(recordingFile);
        return new Result(exerisAllocs, recordingFile);
    }

    // =========================================================================
    // Assertion helpers — Enterprise vs Community
    // =========================================================================

    /**
     * Asserts the Enterprise zero-allocation contract: zero {@code eu.exeris.*} heap
     * objects in the steady-state phase.
     */
    public static void assertZeroExerisAllocations(Result result, String hotPathDescription) {
        assertThat(result.exerisAllocations())
                .as("Enterprise hot path (%s) must allocate zero eu.exeris.* heap objects. "
                  + "Bootstrap allocations are excluded — recording started after createXxx(). "
                  + "If this fails: check for autoboxing, String.format(), or new collection "
                  + "instances on the hot path.\nDetected classes: %s",
                    hotPathDescription, result.summary())
                .isEmpty();
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
        rec.enable("jdk.ObjectAllocationInNewTLAB").withThreshold(Duration.ZERO);
        rec.enable("jdk.ObjectAllocationOutsideTLAB").withThreshold(Duration.ZERO);
        rec.enable("jdk.ObjectAllocationSample").withThreshold(Duration.ZERO);
    }

    /**
     * Reads a JFR recording and returns all allocation events where the allocated
     * object type belongs to the {@code eu.exeris.*} package hierarchy.
     */
    static List<RecordedEvent> collectExerisAllocations(Path jfrFile) throws IOException {
        List<RecordedEvent> result = new ArrayList<>();
        try (RecordingFile jfr = new RecordingFile(jfrFile)) {
            while (jfr.hasMoreEvents()) {
                RecordedEvent e = jfr.readEvent();
                String type = e.getEventType().getName();
                if ("jdk.ObjectAllocationInNewTLAB".equals(type)
                        || "jdk.ObjectAllocationOutsideTLAB".equals(type)
                        || "jdk.ObjectAllocationSample".equals(type)) {
                    RecordedClass objectClass = e.getValue("objectClass");
                    if (objectClass != null
                            && objectClass.getName().startsWith(EXERIS_PACKAGE)) {
                        result.add(e);
                    }
                }
            }
        }
        return result;
    }

    /** Produces a concise class-name → count summary for assertion messages. */
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

