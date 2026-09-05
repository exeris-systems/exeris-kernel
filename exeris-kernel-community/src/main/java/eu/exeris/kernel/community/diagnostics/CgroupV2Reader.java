/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.diagnostics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Best-effort reader for the Linux cgroup-v2 unified hierarchy and procfs/sysfs file values.
 *
 * <p>Every read is defensive: a missing file, parse failure, {@link SecurityException}, or non-Linux
 * host resolves the affected value to {@link Optional#empty()} (ADR-033 Obligation 5 degradation
 * contract) — never an exception out of this class. Cold-path tooling code, so these file reads are
 * outside the runtime hot-path I/O bans.
 *
 * @since 0.9
 */
final class CgroupV2Reader {

    private static final Path CGROUP_SELF = Path.of("/proc/self/cgroup");
    private static final Path CGROUP_ROOT = Path.of("/sys/fs/cgroup");
    private static final Path CGROUP_CONTROLLERS = CGROUP_ROOT.resolve("cgroup.controllers");
    private static final String UNLIMITED = "max";
    private static final long DEFAULT_CPU_PERIOD_MICROS = 100_000L;

    /** Resolved cgroup-v2 limits; every field is {@link Optional#empty()} when unlimited / absent. */
    /* default */ record CgroupLimits(Optional<Long> cpuQuotaMicros, Optional<Long> cpuPeriodMicros,
                        Optional<Long> memoryMaxBytes, Optional<String> cpusetEffective) {

        /* default */ static final CgroupLimits NONE =
                new CgroupLimits(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private CgroupV2Reader() {
    }

    /* default */ static CgroupLimits read() {
        Optional<Path> base = base();
        if (base.isEmpty()) {
            return CgroupLimits.NONE;
        }
        Path dir = base.get();
        long[] cpuMax = field(dir, "cpu.max").map(CgroupV2Reader::parseCpuMax).orElse(null);
        Optional<Long> cpuQuota = cpuMax == null || cpuMax[0] < 0 ? Optional.empty() : Optional.of(cpuMax[0]);
        Optional<Long> cpuPeriod = cpuMax == null ? Optional.empty() : Optional.of(cpuMax[1]);
        return new CgroupLimits(
                cpuQuota,
                cpuPeriod,
                field(dir, "memory.max").flatMap(CgroupV2Reader::parseUnlimitedLong),
                field(dir, "cpuset.cpus.effective").filter(s -> !s.isBlank()));
    }

    /** Reads a file's full text, or empty when unreadable / absent. Shared with non-cgroup sysfs reads. */
    /* default */ static Optional<String> readFile(Path path) {
        try {
            if (!Files.isReadable(path)) {
                return Optional.empty();
            }
            return Optional.of(Files.readString(path));
        } catch (IOException | SecurityException e) {
            return Optional.empty();
        }
    }

    /** Resolves the cgroup-v2 directory for this process, or empty when the host is not cgroup-v2. */
    private static Optional<Path> base() {
        if (!Files.exists(CGROUP_CONTROLLERS)) {
            return Optional.empty();
        }
        // /proc/self/cgroup is "hierarchy:controllers:path"; the cgroup-v2 line has hierarchy 0 and an
        // empty controller field. Controller files live at /sys/fs/cgroup<path> when the namespace
        // exposes the full path, else directly under the root.
        Optional<Path> scoped = readFile(CGROUP_SELF)
                .flatMap(CgroupV2Reader::unifiedPath)
                .map(rel -> "/".equals(rel) ? CGROUP_ROOT : Path.of(CGROUP_ROOT.toString(), rel))
                .filter(Files::isDirectory);
        // Fallback to the root cgroup when no scoped v2 path resolves (e.g. cgroup-v1/v2 hybrid where the
        // process's v2 path isn't exposed): limits read here may be system-wide, not container-scoped.
        // Acceptable — all values are informational (ADR-008 advisor layers actionable thresholds).
        return scoped.isPresent() ? scoped : Optional.of(CGROUP_ROOT);
    }

    private static Optional<String> unifiedPath(String procSelfCgroup) {
        return procSelfCgroup.lines()
                .map(line -> line.split(":", 3))
                .filter(f -> f.length == 3 && "0".equals(f[0]) && f[1].isEmpty())
                .map(f -> f[2].strip())
                .filter(p -> !p.isEmpty())
                .findFirst();
    }

    private static Optional<String> field(Path base, String file) {
        return readFile(base.resolve(file)).map(String::strip).filter(s -> !s.isEmpty());
    }

    /**
     * Parses {@code cpu.max} ({@code "<quota|max> <period>"}) into {@code [quotaMicros, periodMicros]};
     * a quota of {@code -1} means unlimited ({@code "max"}).
     */
    private static long[] parseCpuMax(String raw) {
        String[] parts = raw.split("\\s+");
        long quota = parts.length > 0 && !UNLIMITED.equals(parts[0]) ? parseLong(parts[0], -1L) : -1L;
        long period = parts.length > 1 ? parseLong(parts[1], DEFAULT_CPU_PERIOD_MICROS) : DEFAULT_CPU_PERIOD_MICROS;
        return new long[] {quota, period};
    }

    private static Optional<Long> parseUnlimitedLong(String raw) {
        if (UNLIMITED.equals(raw)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(raw));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static long parseLong(String raw, long fallback) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
