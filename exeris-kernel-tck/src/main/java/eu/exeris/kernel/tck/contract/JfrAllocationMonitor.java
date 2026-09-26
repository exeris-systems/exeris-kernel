/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract;

// CHECKSTYLE:OFF: com.sun.management.ThreadMXBean is the ONLY way to read exact per-thread
//                 allocated bytes, and this class exists to cross-check JFR's sampled figure
//                 against an unsampled one — the defect that made the graph churn TCK report a
//                 sampler draw rather than a byte count. The kernel ban on com.sun is right for
//                 runtime code; a measurement instrument is what it is measuring with.
//                 Surfaced by moving these classes to src/main: while they were test sources the
//                 ban could not see this import at all.
import com.sun.management.ThreadMXBean;
// CHECKSTYLE:ON
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

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
 * <h2>Two Signals</h2>
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
 * by <em>allocated type</em> is invariant to inlining depth. The count therefore includes
 * every {@code eu.exeris.*} object the workload thread allocated, whoever called the allocating
 * code: a production allocation made because a test invoked it is still a production allocation.
 * Which <em>frame</em> owns an allocation is a different question, answered by
 * {@code tools/jfr-reporter} over the same recording, and it answers it for every object type,
 * not only {@code eu.exeris.*}.
 *
 * <h2>Window Marker</h2>
 * <p>The steady-state recording carries a pair of {@link AllocationWindowEvent}s — {@code start}
 * and {@code end} — that name the subsystem, the test class, the workload thread, the
 * {@link Contract} asserted and the measured bytes delta, so the recording explains itself without
 * its file name. Both event objects are allocated before the recording starts and both commits
 * fall outside the {@code ThreadMXBean} bracket, so a zero-allocation contract cannot see them.
 *
 * @since 0.5
 */
public final class JfrAllocationMonitor {

    /**
     * The {@code boundary} of the marker that opens a measurement window.
     *
     * @since 0.12
     */
    public static final String BOUNDARY_START = "start";

    /**
     * The {@code boundary} of the marker that closes a window whose workload completed.
     *
     * @since 0.12
     */
    public static final String BOUNDARY_END = "end";

    /**
     * The {@code boundary} of the marker that closes a window whose workload threw. Distinct from
     * {@link #BOUNDARY_END} because a truncated window is not evidence: an aborted measurement that
     * happened to sample no allocation before the throw would otherwise read as compliance.
     *
     * @since 0.12
     */
    public static final String BOUNDARY_ABORT = "abort";

    private static final String EXERIS_PACKAGE = "eu.exeris.";
    private static final String MARKER_CLASS = AllocationWindowEvent.class.getName();
    private static final DateTimeFormatter JFR_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * High half of every {@code measurementId}: one nonce per JVM, so ids minted in two forks stay
     * distinct if their recordings are ever read together. Class-load work, not per-measurement.
     */
    private static final long JVM_NONCE =
            ThreadLocalRandom.current().nextLong() & 0xFFFF_FFFF_0000_0000L;

    /** Low half: monotonic within the JVM. {@code incrementAndGet} returns a primitive — no allocation. */
    private static final AtomicLong MEASUREMENT_SEQ = new AtomicLong();

    /**
     * Characters the recording's file name may not contain. The counterpart is
     * {@code RecordingIdentity.TCK_FILE} in {@code tools/jfr-reporter}, which parses
     * {@code <testClass>-<subsystem>-<yyyyMMdd>-<HHmmss>.jfr} and admits no dash inside either
     * token — deliberately, because that is what distinguishes these files from the pinning
     * monitor's {@code pin-<label>-<ts>.jfr}. The two builds have no dependency on each other, so
     * the classes are stated twice and checked against each other rather than shared.
     *
     * <p>Sanitising here and not in the marker is the point: the subsystem keeps its published
     * spelling ({@code Graph-ChurnRatio}) in the event, and only the file name is made parseable.
     */
    private static final String SUBSYSTEM_TOKEN_BAN = "[^A-Za-z0-9_]";
    private static final String TEST_CLASS_TOKEN_BAN = "[^A-Za-z0-9_$]";

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
     * @param contract          the contract the caller will assert, written into the recording's
     *                          window marker; {@link Contract#unspecified()} when the caller
     *                          asserts something of its own
     */
    public record Config(
            String subsystemName,
            String testClassName,
            int warmupIterations,
            int hotPathIterations,
            Contract contract
    ) {
        /**
         * Validates the iteration counts; a {@code null} contract reads as unspecified.
         *
         * @param subsystemName     human-readable name (e.g. "Memory", "Transport")
         * @param testClassName     simple class name of the calling test (for file naming)
         * @param warmupIterations  iterations for the warm-up (discarded) phase
         * @param hotPathIterations iterations for the steady-state (measured) phase
         * @param contract          the contract the caller will assert, or {@code null}
         * @throws IllegalArgumentException if {@code warmupIterations} is negative or
         *                                   {@code hotPathIterations} is less than 1
         */
        public Config {
            if (warmupIterations < 0) {
                throw new IllegalArgumentException("warmupIterations must be >= 0");
            }
            if (hotPathIterations < 1) {
                throw new IllegalArgumentException("hotPathIterations must be >= 1");
            }
            contract = contract == null ? Contract.unspecified() : contract;
        }

        /**
         * A config whose contract is {@link Contract#unspecified()} — the shape every caller used
         * before the window marker existed.
         *
         * @param subsystemName     human-readable name (e.g. "Memory", "Transport")
         * @param testClassName     simple class name of the calling test (for file naming)
         * @param warmupIterations  iterations for the warm-up (discarded) phase
         * @param hotPathIterations iterations for the steady-state (measured) phase
         */
        public Config(String subsystemName, String testClassName, int warmupIterations, int hotPathIterations) {
            this(subsystemName, testClassName, warmupIterations, hotPathIterations, Contract.unspecified());
        }

        /**
         * Default config: 1 000 warmup, 10 000 steady-state.
         *
         * @param subsystemName human-readable name (e.g. "Memory", "Transport")
         * @param testClassName simple class name of the calling test (for file naming)
         * @return a config with 1 000 warm-up and 10 000 steady-state iterations
         */
        public static Config ofDefaults(String subsystemName, String testClassName) {
            return new Config(subsystemName, testClassName, 1_000, 10_000);
        }

        /**
         * Default config with a stated contract: 1 000 warmup, 10 000 steady-state.
         *
         * @param subsystemName human-readable name (e.g. "Memory", "Transport")
         * @param testClassName simple class name of the calling test (for file naming)
         * @param contract      the contract the caller will assert
         * @return a config with 1 000 warm-up and 10 000 steady-state iterations
         * @since 0.12
         */
        public static Config ofDefaults(String subsystemName, String testClassName, Contract contract) {
            return new Config(subsystemName, testClassName, 1_000, 10_000, contract);
        }

        /**
         * High-density config for E2E integrity: 1 000 warmup, 1 000 000 steady-state.
         *
         * @param subsystemName human-readable name (e.g. "Memory", "Transport")
         * @param testClassName simple class name of the calling test (for file naming)
         * @return a config with 1 000 warm-up and 1 000 000 steady-state iterations
         */
        public static Config ofHighDensity(String subsystemName, String testClassName) {
            return new Config(subsystemName, testClassName, 1_000, 1_000_000);
        }

        /**
         * High-density config with a stated contract: 1 000 warmup, 1 000 000 steady-state.
         *
         * @param subsystemName human-readable name (e.g. "Memory", "Transport")
         * @param testClassName simple class name of the calling test (for file naming)
         * @param contract      the contract the caller will assert
         * @return a config with 1 000 warm-up and 1 000 000 steady-state iterations
         * @since 0.12
         */
        public static Config ofHighDensity(String subsystemName, String testClassName, Contract contract) {
            return new Config(subsystemName, testClassName, 1_000, 1_000_000, contract);
        }
    }

    /**
     * The contract a measurement is asserted against, written into the recording's
     * {@link AllocationWindowEvent} so a reader can reproduce the verdict.
     *
     * @param mode                    zero, bounded, bounded-bytes, or unspecified
     * @param budgetPerIteration      the bounded budget of {@code eu.exeris.*} allocations per
     *                                iteration; {@code -1} when the mode has none
     * @param budgetBytesPerIteration the bounded byte budget per iteration; {@code -1} when the mode
     *                                has none
     * @since 0.12
     */
    public record Contract(Mode mode, int budgetPerIteration, double budgetBytesPerIteration) {

        /**
         * How a measurement is judged.
         *
         * @since 0.12
         */
        public enum Mode {
            /** Zero {@code eu.exeris.*} events and fewer bytes than iterations — {@link #assertZeroExerisAllocations}. */
            ZERO,
            /** At most {@code iterations × budget} events — {@link #assertBoundedExerisAllocations}. */
            BOUNDED,
            /**
             * Fewer than {@code budgetBytesPerIteration} allocated bytes per iteration —
             * {@link #assertBoundedBytesPerIteration}. The one mode that binds bytes rather than
             * typed events, for hot paths whose cost is measured by volume.
             */
            BOUNDED_BYTES,
            /** The caller asserts something of its own; a reader reports the window as not measured. */
            UNSPECIFIED;

            /** The lower-case spelling written into the marker. */
            String marker() {
                return name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
            }
        }

        /**
         * Validates the pair.
         *
         * @param mode               zero, bounded, or unspecified
         * @param budgetPerIteration the bounded budget, or {@code -1}
         * @throws IllegalArgumentException if a bounded contract has a negative budget
         */
        public Contract {
            if (mode == null) {
                throw new IllegalArgumentException("mode must not be null");
            }
            if (mode == Mode.BOUNDED && budgetPerIteration < 0) {
                throw new IllegalArgumentException("budgetPerIteration must be >= 0 for a bounded contract");
            }
            // NaN spelled out: it is neither > 0 nor <= 0, and a NaN budget would make every
            // comparison against it false, so the contract would be unfailable.
            if (mode == Mode.BOUNDED_BYTES
                    && (Double.isNaN(budgetBytesPerIteration) || budgetBytesPerIteration <= 0)) {
                throw new IllegalArgumentException(
                        "budgetBytesPerIteration must be > 0 for a bounded-bytes contract");
            }
        }

        /**
         * The zero-allocation contract.
         *
         * @return a contract of mode {@link Mode#ZERO}
         * @since 0.12
         */
        public static Contract zero() {
            return new Contract(Mode.ZERO, -1, -1.0);
        }

        /**
         * The bounded-allocation contract.
         *
         * @param budgetPerIteration maximum {@code eu.exeris.*} allocations per iteration
         * @return a contract of mode {@link Mode#BOUNDED}
         * @since 0.12
         */
        public static Contract bounded(int budgetPerIteration) {
            return new Contract(Mode.BOUNDED, budgetPerIteration, -1.0);
        }

        /**
         * The bounded-bytes contract: fewer than this many allocated bytes per iteration.
         *
         * <p>A ratio against a fixed quantity of work is the same statement. A churn bound of
         * {@code r} times a payload of {@code p} bytes per iteration is a byte budget of
         * {@code r × p}, and expressing it that way lets a reader reproduce the verdict from the
         * marker alone — it has the bytes delta and the iteration count, but not the payload.
         *
         * @param budgetBytesPerIteration the exclusive upper bound, in bytes per iteration
         * @return a contract of mode {@link Mode#BOUNDED_BYTES}
         * @since 0.12
         */
        public static Contract bytesPerIteration(double budgetBytesPerIteration) {
            return new Contract(Mode.BOUNDED_BYTES, -1, budgetBytesPerIteration);
        }

        /**
         * No contract stated to the recording.
         *
         * @return a contract of mode {@link Mode#UNSPECIFIED}
         * @since 0.12
         */
        public static Contract unspecified() {
            return new Contract(Mode.UNSPECIFIED, -1, -1.0);
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
     *                            cross-check immune to TLAB-refill granularity.
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
         *
         * @return the class×count summary followed by the thread-allocated-bytes delta
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
            } finally {
                // Outside the try, a warm-up that threw would orphan this file in the system temp
                // directory for the life of the machine.
                Files.deleteIfExists(warmupFile);
            }
        }

        // ── STEADY-STATE RECORDING ───────────────────────────────────────────
        Path reportsDir = Path.of("target", "jfr-reports");
        Files.createDirectories(reportsDir);
        String timestamp = LocalDateTime.now().format(JFR_TS);
        String fileName = nameToken(config.testClassName(), TEST_CLASS_TOKEN_BAN) + "-"
                + nameToken(config.subsystemName(), SUBSYSTEM_TOKEN_BAN) + "-" + timestamp + ".jfr";
        Path recordingFile = reportsDir.resolve(fileName);

        // The workload runs synchronously on THIS thread; scope the allocation accounting to it so a
        // concurrent/leaked background thread (e.g. a transport carrier reactor from a prior test
        // still draining) cannot contaminate the measurement with its own eu.exeris.* allocations.
        // JFR records all threads globally — we filter collected events by the workload thread id.
        long workloadThreadId = Thread.currentThread().threadId();

        // Both marker objects exist before the recording starts, so no sample inside the window can be
        // attributed to them; their commits sit outside the ThreadMXBean bracket below, so the bytes
        // delta never contains them. AllocationWindowEvent's Javadoc states this invariant and
        // AllocationWindowMarkerSelfTest pins it against a zero-allocation assertion.
        long measurementId = JVM_NONCE | (MEASUREMENT_SEQ.incrementAndGet() & 0xFFFF_FFFFL);
        AllocationWindowEvent startMarker = newMarker(BOUNDARY_START, config, workloadThreadId, measurementId);
        AllocationWindowEvent endMarker = newMarker(BOUNDARY_END, config, workloadThreadId, measurementId);

        // JFR-independent cross-check: ThreadMXBean reports exact per-thread allocated
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
            startMarker.commit();

            try {
                long allocBefore = threadAllocatedBytes(threadMx, workloadThreadId);
                workload.run(config.hotPathIterations());
                long allocAfter = threadAllocatedBytes(threadMx, workloadThreadId);
                allocatedBytesDelta = (allocBefore == ALLOCATED_BYTES_UNAVAILABLE
                        || allocAfter == ALLOCATED_BYTES_UNAVAILABLE)
                        ? ALLOCATED_BYTES_UNAVAILABLE
                        : allocAfter - allocBefore;
            } catch (RuntimeException | Error t) {
                // The window closes on every path. An orphaned start is not a local defect: the
                // same marker lands in the JVM-wide target/surefire.jfr, where a reader matches it
                // with a LATER measurement's closing marker and mis-attributes that measurement.
                //
                // It closes as "abort", never as "end", because a window truncated at a throw is
                // not evidence: an aborted run that sampled nothing before the throw would
                // otherwise satisfy a zero-allocation contract and read as compliance.
                //
                // Only statements that allocate nothing on the workload thread belong here, and
                // the bytes delta is deliberately left unread: allocAfter must NOT move into this
                // block, because the throwable's construction and stack-trace fill happen first
                // and would be counted into it.
                endMarker.boundary = BOUNDARY_ABORT;
                endMarker.commit();
                rec.stop();
                throw t;
            }

            endMarker.allocatedBytesDelta = allocatedBytesDelta;
            endMarker.commit();
            rec.stop();
        } finally {
            if (restoreAllocTracking) {
                threadMx.setThreadAllocatedMemoryEnabled(false);
            }
        }

        List<RecordedEvent> exerisAllocs = collectExerisAllocations(recordingFile, workloadThreadId);
        return new Result(exerisAllocs, allocatedBytesDelta, config.hotPathIterations(), recordingFile);
    }

    private static String nameToken(String raw, String banned) {
        String token = raw == null ? "" : raw.replaceAll(banned, "_");
        return token.isEmpty() ? "unnamed" : token;
    }

    private static AllocationWindowEvent newMarker(String boundary, Config config,
                                                   long workloadThreadId, long measurementId) {
        AllocationWindowEvent marker = new AllocationWindowEvent();
        marker.boundary = boundary;
        marker.measurementId = measurementId;
        marker.subsystem = config.subsystemName();
        marker.testClass = config.testClassName();
        marker.iterations = config.hotPathIterations();
        marker.workloadThreadId = workloadThreadId;
        marker.contractMode = config.contract().mode().marker();
        marker.budgetPerIteration = config.contract().budgetPerIteration();
        marker.budgetBytesPerIteration = config.contract().budgetBytesPerIteration();
        marker.allocatedBytesDelta = ALLOCATED_BYTES_UNAVAILABLE;
        return marker;
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
     * <p>Two independent signals are checked: the {@code eu.exeris.*} JFR allocation
     * events (attribute <em>what</em> allocated, but under-report small steady allocations that fit
     * inside an active TLAB), and the {@code ThreadMXBean} per-thread allocated-bytes delta (exact,
     * TLAB-granularity-immune — proves the absence). A truly zero-allocation hot path satisfies both.
     *
     * @param result             result from {@link #measure}
     * @param hotPathDescription human-readable description for assertion messages
     */
    public static void assertZeroExerisAllocations(Result result, String hotPathDescription) {
        assertThat(result.exerisAllocations())
                .as("Enterprise hot path (%s) must allocate zero eu.exeris.* heap objects. "
                                + "Bootstrap allocations are excluded — recording started after createXxx(). "
                                + "If this fails: check for autoboxing, String.format(), or new collection "
                                + "instances on the hot path.\nDetected classes: %s",
                        hotPathDescription, result.summary())
                .isEmpty();

        // JFR-independent proof: a zero-allocation hot path has no per-iteration
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
     * Asserts the bytes-per-iteration contract: fewer than {@code budgetBytesPerIteration} bytes
     * of thread-allocated memory per iteration.
     *
     * <p>Fails, rather than skips, when the JVM cannot report per-thread allocated bytes: a silent
     * pass would read as compliance with a contract nothing measured.
     *
     * @param result                  result from {@link #measure}
     * @param budgetBytesPerIteration the exclusive upper bound, in bytes per iteration
     * @param hotPathDescription      what was measured, for the failure message
     * @since 0.12
     */
    public static void assertBoundedBytesPerIteration(Result result, double budgetBytesPerIteration,
                                                      String hotPathDescription) {
        assertThat(result.allocatedBytesDelta())
                .as("This JVM does not report per-thread allocated bytes, so the byte budget for "
                    + "%s cannot be certified. Failing rather than skipping: a silent pass would "
                    + "read as compliance.", hotPathDescription)
                .isNotEqualTo(ALLOCATED_BYTES_UNAVAILABLE);
        double perIteration = (double) result.allocatedBytesDelta() / result.hotPathIterations();
        assertThat(perIteration)
                .as("Hot path (%s) allocated %.0f bytes per iteration, budget is %.0f "
                    + "(%d bytes over %d iterations). %s",
                        hotPathDescription, perIteration, budgetBytesPerIteration,
                        result.allocatedBytesDelta(), result.hotPathIterations(), result.summary())
                .isLessThan(budgetBytesPerIteration);
    }

    /**
     * Asserts whatever contract the {@link Config} states, so the asserted bound and the bound
     * written into the recording cannot be two different numbers.
     *
     * <p>Passing the budget as an argument to {@link #assertBoundedExerisAllocations} while the
     * config states another is a divergence a compiler cannot see; this method removes the
     * argument, and with it the divergence.
     *
     * @param config             the config the measurement ran under
     * @param result             result from {@link #measure}
     * @param hotPathDescription what was measured, for the failure message
     * @throws IllegalArgumentException if the config states no contract — such a caller must assert
     *                                  its own property, and say so by not calling this
     * @since 0.12
     */
    public static void assertContract(Config config, Result result, String hotPathDescription) {
        Contract contract = config.contract();
        switch (contract.mode()) {
            case ZERO -> assertZeroExerisAllocations(result, hotPathDescription);
            case BOUNDED -> assertBoundedExerisAllocations(result, config.hotPathIterations(),
                    contract.budgetPerIteration(), hotPathDescription);
            case BOUNDED_BYTES -> assertBoundedBytesPerIteration(result,
                    contract.budgetBytesPerIteration(), hotPathDescription);
            case UNSPECIFIED -> throw new IllegalArgumentException(
                    "Config states no contract, so there is nothing for assertContract to assert: "
                    + hotPathDescription);
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
        // Allocation events have no duration, so withThreshold(...) is a no-op on all three.
        // InNewTLAB/OutsideTLAB only fire on a TLAB refill / large allocation, so
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
     * to overlap the recording window. The {@link AllocationWindowEvent} objects are excluded by
     * class as well: they are allocated before the recording starts, so a sample cannot land on
     * them, and the exclusion states that rather than relies on it.
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
                            && !MARKER_CLASS.equals(objectClass.getName())
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
