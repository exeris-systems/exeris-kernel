/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.diagnostics;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, read-only view of the JVM and container environment the kernel actually runs in, returned
 * by {@link KernelDiagnostics#getJvmErgonomics()}.
 *
 * <p>Strictly <em>observational</em>: it reports GC, heap geometry, the resolved processor count, the
 * cgroup-v2 CPU / memory / cpuset limits the runtime is squeezed by, and best-effort large-pages / THP /
 * CDS / AOT state. It carries <b>no</b> recommendation fields — the actionable tuning advisor (threshold
 * ladder, "you are throttled, raise X") is the Enterprise advisor's surface per ADR-008, layered on top
 * of this same record shape.
 *
 * <h2>Degradation contract (ADR-033 Obligation 5)</h2>
 * <p>Data that is legitimately absent — a non-Linux host, a cgroup-v1-only hierarchy, no container
 * limits, a non-HotSpot JVM that cannot report a knob — is modelled as {@link Optional#empty()} on the
 * affected field, never a sentinel value or a new "unknown" type. The always-available JVM facts
 * ({@code gcName}, {@code availableProcessors}, heap geometry) are plain fields; {@code heapMaxBytes} is
 * {@code -1} when the JVM reports the max as undefined (mirrors {@code java.lang.management}).
 *
 * <p>This record joins the ADR-033 snapshot family: {@code schemaVersion} first, then {@code capturedAt},
 * with append-only growth governed by {@link KernelDiagnostics#SCHEMA_VERSION}.
 *
 * <h2>Valhalla readiness — explicit tradeoff</h2>
 * <p>This carrier deliberately uses {@link Optional} components (an identity class today) over
 * sentinel-{@code long}s + a presence bitmask: it is a cold-path diagnostic snapshot (never on a hot
 * path or in an allocation budget), and the {@code Optional.empty()} degradation contract is materially
 * clearer than sentinels. A future Valhalla value-class migration would flatten the {@code long}/{@code int}
 * components but not the {@code Optional} ones — that is an accepted, documented cost for this record, not
 * an oversight to re-litigate during a Valhalla sweep.
 *
 * @param schemaVersion         the wire-schema version (see {@link KernelDiagnostics#SCHEMA_VERSION})
 * @param capturedAt            the instant this snapshot was captured (best-effort, per-call)
 * @param gcName                the active garbage collector(s), comma-joined; never blank
 * @param heapMaxBytes          the maximum heap in bytes, or {@code -1} if the JVM reports it undefined
 *                              (also {@code -1} in the degraded {@link #unknown()} snapshot)
 * @param heapCommittedBytes    the committed heap in bytes ({@code >= 0} on a running JVM), or {@code -1}
 *                              in the degraded {@link #unknown()} snapshot
 * @param availableProcessors   {@link Runtime#availableProcessors()} as the JVM resolved it
 * @param cpuQuotaMicros        cgroup-v2 {@code cpu.max} quota (μs per period); empty when unlimited / absent
 * @param cpuPeriodMicros       cgroup-v2 {@code cpu.max} period (μs); empty when absent
 * @param memoryMaxBytes        cgroup-v2 {@code memory.max} in bytes; empty when unlimited / absent
 * @param cpusetEffective       cgroup-v2 {@code cpuset.cpus.effective} raw list (e.g. {@code "0-3"}); empty when absent
 * @param largePagesEnabled     whether large pages are requested ({@code -XX:+UseLargePages}); empty if unknown
 * @param transparentHugePages  whether the host THP mode is active (not {@code [never]}); empty if unknown
 * @param classDataSharingActive whether CDS is active; empty if undeterminable
 * @param aotCacheActive        whether an AOT cache is active; empty if undeterminable
 * @since 0.9.0
 */
public record RuntimeErgonomicsSnapshot(
        String schemaVersion,
        Instant capturedAt,
        String gcName,
        long heapMaxBytes,
        long heapCommittedBytes,
        int availableProcessors,
        Optional<Long> cpuQuotaMicros,
        Optional<Long> cpuPeriodMicros,
        Optional<Long> memoryMaxBytes,
        Optional<String> cpusetEffective,
        Optional<Boolean> largePagesEnabled,
        Optional<Boolean> transparentHugePages,
        Optional<Boolean> classDataSharingActive,
        Optional<Boolean> aotCacheActive) {

    public RuntimeErgonomicsSnapshot {
        Objects.requireNonNull(schemaVersion, "schemaVersion");
        Objects.requireNonNull(capturedAt, "capturedAt");
        Objects.requireNonNull(gcName, "gcName");
        Objects.requireNonNull(cpuQuotaMicros, "cpuQuotaMicros");
        Objects.requireNonNull(cpuPeriodMicros, "cpuPeriodMicros");
        Objects.requireNonNull(memoryMaxBytes, "memoryMaxBytes");
        Objects.requireNonNull(cpusetEffective, "cpusetEffective");
        Objects.requireNonNull(largePagesEnabled, "largePagesEnabled");
        Objects.requireNonNull(transparentHugePages, "transparentHugePages");
        Objects.requireNonNull(classDataSharingActive, "classDataSharingActive");
        Objects.requireNonNull(aotCacheActive, "aotCacheActive");
    }

    /**
     * The minimal, fully-degraded snapshot used by the {@link KernelDiagnostics#getJvmErgonomics()}
     * default method: real {@code availableProcessors}, an {@code "unknown"} GC, undefined heap, and every
     * environment-sensitive field {@link Optional#empty()}. A {@link KernelDiagnosticsProvider} that does
     * not override the method stays binary-compatible and still returns a valid, non-null snapshot.
     */
    public static RuntimeErgonomicsSnapshot unknown() {
        return new RuntimeErgonomicsSnapshot(
                KernelDiagnostics.SCHEMA_VERSION,
                Instant.now(),
                "unknown",
                -1L,
                -1L,
                Runtime.getRuntime().availableProcessors(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }
}
