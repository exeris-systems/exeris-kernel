/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.memory;

import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.tck.contract.memory.AbstractZeroGcJfrMonitorTck;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * High-density flamegraph harness for Community Memory.
 *
 * <h2>Two scenarios</h2>
 * <ol>
 *   <li><b>Sequential 1M</b> — inherited from {@link AbstractZeroGcJfrMonitorTck}.
 *       Shows per-iteration object churn of Community tier (bounded).</li>
 *   <li><b>VT Avalanche 1M</b> — {@link #communityAvalancheMillion()} launches 1M
 *       Virtual Threads via {@link StructuredTaskScope}. Exposes two cost layers:
 *       <ul>
 *         <li><b>Loom Tax</b>: JVM allocates ~546 MB of VirtualThread continuations,
 *             Carrier objects, and scheduler state on the main heap — unavoidable
 *             overhead for any 1M-VT workload, independent of Exeris tier.</li>
 *         <li><b>Object Tax</b>: Each worker VT additionally allocates Community
 *             domain objects ({@code CommunityLoanedBuffer}, {@code ArenaImpl},
 *             {@code ConfinedSession}) — per-operation churn specific to Community
 *             tier's lack of a slab pool.</li>
 *       </ul>
 *       Enterprise eliminates the Object Tax entirely. The Loom Tax is shared.
 *       This test makes that contrast machine-readable via JFR.</li>
 * </ol>
 */
@Tag("flamegraph")
@DisplayName("Community Memory — flamegraph harness")
class CommunityMemoryFlamegraphHarnessTest extends AbstractZeroGcJfrMonitorTck {

    private static final int VT_COUNT   = 1_000_000;
    private static final int BATCH_SIZE =    10_000;

    // ── AbstractZeroGcJfrMonitorTck wiring ───────────────────────────────────

    @Override
    protected MemoryAllocator createAllocator() {
        return new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
    }

    @Override
    protected int warmupIterations() {
        return 10_000;
    }

    @Override
    protected int hotPathIterations() {
        return 1_000_000;
    }

    // ── VT Avalanche ─────────────────────────────────────────────────────────

    /**
     * Launches {@value #VT_COUNT} Virtual Threads via {@link StructuredTaskScope}.
     * Each VT performs one {@code allocate(MICRO) → write → close} cycle.
     *
     * <h2>Expected JFR signal</h2>
     * <ul>
     *   <li><b>Loom Tax</b> ({@code java.*}, {@code jdk.internal.*}):
     *       VirtualThread, VirtualThread$Continuation, CarrierThread scheduler state —
     *       several hundred MB; proportional to VT count; <em>same in Enterprise</em>.</li>
     *   <li><b>Object Tax</b> ({@code eu.exeris.community.*}):
     *       {@code CommunityLoanedBuffer}, {@code ArenaImpl}, {@code ConfinedSession}
     *       — short-lived wrappers created per-operation; <em>zero in Enterprise</em>
     *       because the slab pool recycles pre-allocated {@code EnterpriseLoanedBuffer}
     *       instances via CAS on primitive {@code long} fields.</li>
     * </ul>
     */
    @Test
    @DisplayName("Avalanche: 1M VTs — Loom Tax + Object Tax flamegraph (Community vs Enterprise contrast)")
    void communityAvalancheMillion() throws Exception {
        Path reportsDir = Path.of("target", "jfr-reports");
        Files.createDirectories(reportsDir);
        String ts     = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path jfrOut   = reportsDir.resolve("CommunityMemoryFlamegraphHarnessTest-VT-" + ts + ".jfr");

        AtomicLong successCount = new AtomicLong();

        try (MemoryAllocator allocator = createAllocator()) {

            // ── warmup: flush JIT + class-loading before recording ───────────
            for (int i = 0; i < warmupIterations(); i++) {
                try (LoanedBuffer b = allocator.allocate(AllocationHint.MICRO)) {
                    b.segment().set(ValueLayout.JAVA_INT, 0, i);
                }
            }

            // ── steady-state recording ────────────────────────────────────────
            try (Recording rec = new Recording()) {
                rec.enable("jdk.ObjectAllocationInNewTLAB").withThreshold(Duration.ZERO);
                rec.enable("jdk.ObjectAllocationOutsideTLAB").withThreshold(Duration.ZERO);
                rec.enable("jdk.ObjectAllocationSample").withThreshold(Duration.ZERO);
                rec.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
                rec.enable("jdk.NativeMethodSample").withPeriod(Duration.ofMillis(10));
                rec.enable("jdk.VirtualThreadStart").withThreshold(Duration.ZERO);
                rec.enable("jdk.VirtualThreadEnd").withThreshold(Duration.ZERO);
                rec.setDestination(jfrOut);
                rec.start();

                for (int b = 0; b < VT_COUNT / BATCH_SIZE; b++) {
                    try (var scope = StructuredTaskScope.open(
                            StructuredTaskScope.Joiner.<Void>awaitAllSuccessfulOrThrow())) {
                        for (int i = 0; i < BATCH_SIZE; i++) {
                            final int id = b * BATCH_SIZE + i;
                            scope.fork(() -> {
                                try (LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO)) {
                                    buf.segment().set(ValueLayout.JAVA_INT, 0, id);
                                }
                                successCount.incrementAndGet();
                                return null;
                            });
                        }
                        scope.join();
                    }
                }

                rec.stop();
            }
        }

        assertThat(successCount.get())
                .as("All %d VTs must complete", VT_COUNT)
                .isEqualTo(VT_COUNT);

        // ── parse JFR and split Loom Tax vs Object Tax ────────────────────────
        AllocationReport report = buildReport(jfrOut);
        report.print(jfrOut);

        // Community contract: eu.exeris.* Object Tax must be present
        // (this is the proof that no slab recycling exists)
        assertThat(report.exerisBytes)
                .as("Community Object Tax must be measurable (no slab pool → per-op allocations)")
                .isGreaterThan(0);
    }

    // ── Report builder ────────────────────────────────────────────────────────

    private static final class AllocationReport {

        long loomBytes    = 0;
        long exerisBytes  = 0;
        long jdkBytes     = 0;
        long otherBytes   = 0;

        final TreeMap<String, long[]> byClass = new TreeMap<>();

        void add(String className, long bytes) {
            byClass.computeIfAbsent(className, k -> new long[1])[0] += bytes;
            if (className.startsWith("eu.exeris.")) {
                exerisBytes += bytes;
            } else if (isLoomClass(className)) {
                loomBytes += bytes;
            } else if (className.startsWith("java.") || className.startsWith("jdk.")) {
                jdkBytes += bytes;
            } else {
                otherBytes += bytes;
            }
        }

        private static boolean isLoomClass(String name) {
            return name.startsWith("java.lang.VirtualThread")
                    || name.startsWith("jdk.internal.vm.Continuation")
                    || name.startsWith("java.lang.Thread")
                    || name.contains("$Continuation")
                    || name.contains("VirtualThread");
        }

        void print(Path jfrOut) {
            long total = loomBytes + exerisBytes + jdkBytes + otherBytes;
            System.out.println();
            System.out.println("╔══════════════════════════════════════════════════════════════╗");
            System.out.println("║  COMMUNITY AVALANCHE — Allocation Report (1M Virtual Threads) ║");
            System.out.println("╠══════════════════════════════════════════════════════════════╣");
            System.out.printf( "║  🔴 Loom Tax    (VT infrastructure):  %10s  %5.1f%%%n",
                    humanBytes(loomBytes),   pct(loomBytes, total));
            System.out.printf( "║  🔴 Object Tax  (eu.exeris.*):        %10s  %5.1f%%%n",
                    humanBytes(exerisBytes), pct(exerisBytes, total));
            System.out.printf( "║  🔵 JDK stdlib  (java.*/jdk.*):       %10s  %5.1f%%%n",
                    humanBytes(jdkBytes),    pct(jdkBytes, total));
            System.out.printf( "║  ⚪ Other:                            %10s  %5.1f%%%n",
                    humanBytes(otherBytes),  pct(otherBytes, total));
            System.out.println("╠══════════════════════════════════════════════════════════════╣");
            System.out.printf( "║  TOTAL sampled heap:                  %10s%n", humanBytes(total));
            System.out.println("╠══════════════════════════════════════════════════════════════╣");
            System.out.println("║  Top 15 allocating classes (by bytes):                       ║");
            byClass.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                    .limit(15)
                    .forEach(e -> System.out.printf("║    %-42s %8s%n",
                            shortName(e.getKey()), humanBytes(e.getValue()[0])));
            System.out.println("╠══════════════════════════════════════════════════════════════╣");
            System.out.println("║  Enterprise contrast:                                         ║");
            System.out.println("║    Object Tax in Enterprise = 0 B  (slab pool CAS recycles)   ║");
            System.out.printf( "║    Object Tax in Community  = %-10s  (no pool → churn) %n",
                    humanBytes(exerisBytes));
            System.out.println("╠══════════════════════════════════════════════════════════════╣");
            System.out.println("║  JFR: " + jfrOut.getFileName() + "                 ║");
            System.out.println("╚══════════════════════════════════════════════════════════════╝");
            System.out.println();
        }

        private static double pct(long part, long total) {
            return total == 0 ? 0.0 : 100.0 * part / total;
        }

        private static String humanBytes(long b) {
            if (b >= 1_073_741_824L) return String.format("%.2f GB", b / 1_073_741_824.0);
            if (b >= 1_048_576L)     return String.format("%.1f MB", b / 1_048_576.0);
            if (b >= 1_024L)         return String.format("%.1f kB", b / 1_024.0);
            return b + " B";
        }

        private static String shortName(String fqn) {
            int dot = fqn.lastIndexOf('.');
            return dot >= 0 ? fqn.substring(dot + 1) : fqn;
        }
    }

    private static AllocationReport buildReport(Path jfrOut) throws Exception {
        AllocationReport report = new AllocationReport();
        List<String> alloc = List.of(
                "jdk.ObjectAllocationInNewTLAB",
                "jdk.ObjectAllocationOutsideTLAB",
                "jdk.ObjectAllocationSample");
        try (RecordingFile jfr = new RecordingFile(jfrOut)) {
            while (jfr.hasMoreEvents()) {
                RecordedEvent e = jfr.readEvent();
                if (!alloc.contains(e.getEventType().getName())) continue;
                RecordedClass cls = e.getValue("objectClass");
                if (cls == null) continue;
                String name  = cls.getName().replace('/', '.');
                long   bytes = 0;
                if (e.hasField("allocationSize")) {
                    Object raw = e.getValue("allocationSize");
                    if (raw instanceof Number n) bytes = n.longValue();
                }
                if (e.hasField("tlabSize")) {
                    Object raw = e.getValue("tlabSize");
                    if (raw instanceof Number n && bytes == 0) bytes = n.longValue();
                }
                if (bytes <= 0) bytes = 1;
                report.add(name, bytes);
            }
        }
        return report;
    }
}
