/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.core.memory;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Core: Probes the physical memory environment at bootstrap time to establish
 * safe off-heap budget boundaries.
 *
 * <h2>Detection Strategy (in priority order)</h2>
 * <ol>
 *   <li><b>cgroup v2</b> — reads {@code /sys/fs/cgroup/memory.max}</li>
 *   <li><b>cgroup v1</b> — reads {@code /sys/fs/cgroup/memory/memory.limit_in_bytes}</li>
 *   <li><b>OS physical RAM</b> — {@code com.sun.management.OperatingSystemMXBean.getTotalMemorySize()}</li>
 *   <li><b>JVM {@code -Xmx}</b> — {@code Runtime.getRuntime().maxMemory()}</li>
 * </ol>
 *
 * <h2>Off-Heap Budget Formula</h2>
 * <p>The recommended off-heap budget = {@code totalMemory - jvmHeapMax - headroomBytes}.
 * The default headroom is {@value #DEFAULT_HEADROOM_BYTES} bytes (256 MB) reserved for
 * OS/kernel overhead, JVM internal structures, and native libraries.
 *
 * <h2>Driver Agnosticism</h2>
 * <p>This class produces a {@link MemoryBoundaries} record consumed by
 * {@code MemoryProviderConfig} builders in the Community/Enterprise subsystem boot path.
 * It has zero knowledge of arenas, slab pools, or io_uring.
 *
 * <h2>JFR-First</h2>
 * <p>Emits a single {@code MemoryEnvironmentProbed} event at probe time for bootstrap
 * waterfall visibility.
 *
 * @since 0.5.0
 */
public final class MemoryEnvironmentProbe {

    /**
     * Default headroom reserved for OS/JVM overhead (256 MB).
     */
    public static final long DEFAULT_HEADROOM_BYTES = 256L * 1024L * 1024L;

    private static final Path CGROUP_V2_MEMORY_MAX =
            Path.of("/sys/fs/cgroup/memory.max");
    private static final Path CGROUP_V1_MEMORY_LIMIT =
            Path.of("/sys/fs/cgroup/memory/memory.limit_in_bytes");

    /**
     * Sentinel returned by cgroup files when no container limit is set.
     */
    private static final String CGROUP_UNLIMITED = "max";

    private MemoryEnvironmentProbe() {
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Probes the runtime memory environment and returns physical boundaries.
     *
     * <p>This method performs filesystem reads (cgroup files) and is intended for
     * bootstrap-time use only — never on the hot path.
     *
     * @return detected memory boundaries; never {@code null}
     */
    public static MemoryBoundaries probe() {
        return probe(DEFAULT_HEADROOM_BYTES);
    }

    /**
     * Probes the runtime memory environment with a custom headroom reservation.
     *
     * @param headroomBytes bytes to reserve for OS/JVM overhead; must be {@code >= 0}
     * @return detected memory boundaries; never {@code null}
     */
    public static MemoryBoundaries probe(long headroomBytes) {
        if (headroomBytes < 0) {
            throw new IllegalArgumentException("headroomBytes must be >= 0, got: " + headroomBytes);
        }

        long totalMemoryBytes = detectTotalMemory();
        long jvmHeapMaxBytes = Runtime.getRuntime().maxMemory();
        long offHeapBudget = Math.max(0L, totalMemoryBytes - jvmHeapMaxBytes - headroomBytes);

        MemoryBoundaries boundaries = new MemoryBoundaries(
                totalMemoryBytes, jvmHeapMaxBytes, offHeapBudget, headroomBytes);

        MemoryEnvironmentProbedEvent.emit(boundaries);
        return boundaries;
    }

    // =========================================================================
    // Detection logic
    // =========================================================================

    /**
     * Detects total available memory using the probe priority chain.
     *
     * <p>OS and cgroup physical limits take strict priority: cgroup v2 is consulted
     * first, then cgroup v1, then the OS physical RAM reported by
     * {@link com.sun.management.OperatingSystemMXBean}. Only when none of those sources
     * can supply a value does this method fall back to
     * {@link Runtime#maxMemory() Runtime.getRuntime().maxMemory()}.
     *
     * @return total memory in bytes; always {@code > 0}
     */
    private static long detectTotalMemory() {
        long cgroupV2 = readCgroupV2Limit();
        if (cgroupV2 > 0) {
            return cgroupV2;
        }

        long cgroupV1 = readCgroupV1Limit();
        if (cgroupV1 > 0) {
            return cgroupV1;
        }

        long osMem = readOsTotalMemory();
        if (osMem > 0) {
            return osMem;
        }

        return Runtime.getRuntime().maxMemory();
    }

    private static long readCgroupV2Limit() {
        try {
            if (!Files.exists(CGROUP_V2_MEMORY_MAX)) {
                return 0L;
            }
            String content = Files.readString(CGROUP_V2_MEMORY_MAX).strip();
            if (CGROUP_UNLIMITED.equals(content)) {
                return 0L;
            }
            return Long.parseLong(content);
        } catch (IOException | NumberFormatException _) {
            return 0L;
        }
    }

    private static long readCgroupV1Limit() {
        try {
            if (!Files.exists(CGROUP_V1_MEMORY_LIMIT)) {
                return 0L;
            }
            String content = Files.readString(CGROUP_V1_MEMORY_LIMIT).strip();
            long limit = Long.parseLong(content);
            return limit > (Long.MAX_VALUE / 2) ? 0L : limit;
        } catch (IOException | NumberFormatException _) {
            return 0L;
        }
    }

    private static long readOsTotalMemory() {
        try {
            java.lang.management.OperatingSystemMXBean osMxBean =
                    ManagementFactory.getOperatingSystemMXBean();
            if (osMxBean instanceof com.sun.management.OperatingSystemMXBean sunOs) {
                return sunOs.getTotalMemorySize();
            }
        } catch (Exception _) { //NOPMD AvoidCatchingGenericException — MXBean access is optional
            // Non-Sun JVM or restricted environment — fall through to JVM heap fallback
        }
        return 0L;
    }

    // =========================================================================
    // MemoryBoundaries — Valhalla-ready result record
    // =========================================================================

    /**
     * Immutable snapshot of detected memory boundaries.
     *
     * <h2>Valhalla Readiness (JEP 401)</h2>
     * <p>All fields are primitive {@code long}s. No identity operations used.
     * Ready for {@code value record} migration.
     *
     * @param totalMemoryBytes        total physical or container-constrained memory
     * @param jvmHeapMaxBytes         JVM max heap in bytes as reported by the runtime
     * @param recommendedOffHeapBytes computed off-heap budget ({@code total - heap - headroom})
     * @param headroomBytes           headroom reservation used in this calculation
     */
    public record MemoryBoundaries(
            long totalMemoryBytes,
            long jvmHeapMaxBytes,
            long recommendedOffHeapBytes,
            long headroomBytes
    ) {
        /**
         * Compact canonical constructor — basic sanity guard.
         */
        public MemoryBoundaries {
            if (totalMemoryBytes < 0) {
                throw new IllegalArgumentException("totalMemoryBytes must be >= 0");
            }
            if (recommendedOffHeapBytes < 0) {
                throw new IllegalArgumentException("recommendedOffHeapBytes must be >= 0");
            }
        }
    }

    // =========================================================================
    // JFR event — emitted once at probe time
    // =========================================================================

    @Name("eu.exeris.kernel.core.MemoryEnvironmentProbed")
    @Label("Memory Environment Probed")
    @Category({"Exeris Kernel", "Memory"})
    @Description("Emitted once at bootstrap when MemoryEnvironmentProbe detects physical memory limits.")
    @StackTrace(false)
    private static final class MemoryEnvironmentProbedEvent extends Event {

        @Label("Total Memory (bytes)")
        /* default */ long totalMemoryBytes;

        @Label("JVM Heap Max (bytes)")
        /* default */ long jvmHeapMaxBytes;

        @Label("Recommended Off-Heap Budget (bytes)")
        /* default */ long recommendedOffHeapBytes;

        @Label("Headroom Reserved (bytes)")
        /* default */ long headroomBytes;

        @Label("cgroup v2 file present")
        /* default */ boolean cgroupV2Present;

        @Label("cgroup v1 file present")
        /* default */ boolean cgroupV1Present;

        private static void emit(MemoryBoundaries bounds) {
            if (!jdk.jfr.FlightRecorder.isInitialized()) {
                return;
            }
            MemoryEnvironmentProbedEvent evt = new MemoryEnvironmentProbedEvent();
            if (evt.isEnabled()) {
                evt.totalMemoryBytes = bounds.totalMemoryBytes();
                evt.jvmHeapMaxBytes = bounds.jvmHeapMaxBytes();
                evt.recommendedOffHeapBytes = bounds.recommendedOffHeapBytes();
                evt.headroomBytes = bounds.headroomBytes();
                evt.cgroupV2Present = Files.exists(CGROUP_V2_MEMORY_MAX);
                evt.cgroupV1Present = Files.exists(CGROUP_V1_MEMORY_LIMIT);
                evt.commit();
            }
        }
    }
}
