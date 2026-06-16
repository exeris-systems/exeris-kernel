/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
 * @since 0.9.0
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
                jvmFlag("UseLargePages"),
                transparentHugePages(),
                classDataSharingActive(),
                aotCacheActive());
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

    private static Optional<Boolean> classDataSharingActive() {
        Optional<Boolean> shareFlag = xshareFlag();
        if (shareFlag.isPresent()) {
            return shareFlag;
        }
        return hasInputArg("-XX:SharedArchiveFile=") ? Optional.of(true) : Optional.empty();
    }

    private static Optional<Boolean> aotCacheActive() {
        if (hasInputArg("-XX:AOTCache=") || hasInputArg("-XX:AOTMode=")) {
            return Optional.of(true);
        }
        return jvmFlag("UseAOTCache");
    }

    private static Optional<Boolean> xshareFlag() {
        for (String arg : inputArguments()) {
            if (arg.startsWith("-Xshare:")) {
                return Optional.of(!"-Xshare:off".equals(arg));
            }
        }
        return Optional.empty();
    }

    /** Reads a boolean {@code -XX:+Flag} / {@code -XX:-Flag} from the JVM input arguments. */
    private static Optional<Boolean> jvmFlag(String flag) {
        for (String arg : inputArguments()) {
            if (arg.equals("-XX:+" + flag)) {
                return Optional.of(true);
            }
            if (arg.equals("-XX:-" + flag)) {
                return Optional.of(false);
            }
        }
        return Optional.empty();
    }

    private static boolean hasInputArg(String prefix) {
        return inputArguments().stream().anyMatch(arg -> arg.startsWith(prefix));
    }

    private static List<String> inputArguments() {
        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
        return runtimeBean.getInputArguments();
    }
}
