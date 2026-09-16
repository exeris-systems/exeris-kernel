/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.diagnostics;

import eu.exeris.kernel.community.diagnostics.CgroupV2Reader.CgroupLimits;
import eu.exeris.kernel.spi.diagnostics.KernelDiagnostics;
import eu.exeris.kernel.spi.diagnostics.RuntimeErgonomicsSnapshot;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Assembles {@link RuntimeErgonomicsSnapshot} from {@code java.lang.management} (GC, heap, JVM input
 * arguments), the {@link CgroupV2Reader} (container CPU / memory / cpuset limits), and sysfs (THP state).
 *
 * <p>Best-effort throughout: any value that cannot be determined on this host degrades to
 * {@link Optional#empty()} (ADR-033 Obligation 5) rather than throwing.
 *
 * @since 0.9
 */
final class CommunityRuntimeErgonomics {

    private static final Path THP_ENABLED = Path.of("/sys/kernel/mm/transparent_hugepage/enabled");

    private CommunityRuntimeErgonomics() {
    }

    // LawOfDemeter: java.lang.management bean access (bean → usage → getMax/getCommitted) is intrinsic to
    // the MXBean API — no value-object alternative exists.
    @SuppressWarnings("PMD.LawOfDemeter")
    /* default */ static RuntimeErgonomicsSnapshot capture() {
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = memoryBean.getHeapMemoryUsage();
        CgroupLimits cgroup = CgroupV2Reader.read();
        List<String> jvmArgs = inputArguments();
        return new RuntimeErgonomicsSnapshot(
                KernelDiagnostics.SCHEMA_VERSION,
                Instant.now(),
                gcName(),
                heap.getMax(),
                heap.getCommitted(),
                Runtime.getRuntime().availableProcessors(),
                cgroup.cpuQuotaMicros(),
                cgroup.cpuPeriodMicros(),
                cgroup.memoryMaxBytes(),
                cgroup.cpusetEffective(),
                jvmFlag(jvmArgs, "UseLargePages"),
                transparentHugePages(),
                classDataSharingActive(jvmArgs),
                aotCacheActive(jvmArgs));
    }

    private static String gcName() {
        String joined = ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(GarbageCollectorMXBean::getName)
                .collect(Collectors.joining(", "));
        return joined.isBlank() ? "unknown" : joined;
    }

    /** Active THP mode from sysfs: present and not {@code [never]} when the host exposes it. */
    private static Optional<Boolean> transparentHugePages() {
        return CgroupV2Reader.readFile(THP_ENABLED).map(s -> !s.contains("[never]"));
    }

    private static Optional<Boolean> classDataSharingActive(List<String> args) {
        Optional<Boolean> shareFlag = xshareFlag(args);
        if (shareFlag.isPresent()) {
            return shareFlag;
        }
        return hasInputArg(args, "-XX:SharedArchiveFile=") ? Optional.of(true) : Optional.empty();
    }

    private static Optional<Boolean> aotCacheActive(List<String> args) {
        if (hasInputArg(args, "-XX:AOTCache=") || hasInputArg(args, "-XX:AOTMode=")) {
            return Optional.of(true);
        }
        return jvmFlag(args, "UseAOTCache");
    }

    private static Optional<Boolean> xshareFlag(List<String> args) {
        for (String arg : args) {
            if (arg.startsWith("-Xshare:")) {
                return Optional.of(!"-Xshare:off".equals(arg));
            }
        }
        return Optional.empty();
    }

    /** Reads a boolean {@code -XX:+Flag} / {@code -XX:-Flag} from the JVM input arguments. */
    private static Optional<Boolean> jvmFlag(List<String> args, String flag) {
        for (String arg : args) {
            if (arg.equals("-XX:+" + flag)) {
                return Optional.of(true);
            }
            if (arg.equals("-XX:-" + flag)) {
                return Optional.of(false);
            }
        }
        return Optional.empty();
    }

    private static boolean hasInputArg(List<String> args, String prefix) {
        return args.stream().anyMatch(arg -> arg.startsWith(prefix));
    }

    private static List<String> inputArguments() {
        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
        return runtimeBean.getInputArguments();
    }
}
