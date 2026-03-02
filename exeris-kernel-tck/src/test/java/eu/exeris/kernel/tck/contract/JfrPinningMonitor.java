/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.tck.contract;

import jdk.jfr.Recording;
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

import static org.assertj.core.api.Assertions.fail;

/**
 * Reusable JFR Carrier Pinning monitor for all subsystem TCKs.
 *
 * <p>Sibling of {@link JfrAllocationMonitor} — placed in the same root {@code contract/}
 * package so every subsystem TCK can import it without cross-package coupling.
 *
 * <p>Captures {@code jdk.VirtualThreadPinned} events during a workload window.
 * Any event exceeding {@link #DEFAULT_THRESHOLD_MS} is recorded as a {@link PinnedEvent}.
 *
 * <p>Threshold default: {@value #DEFAULT_THRESHOLD_MS} ms — tighter than the
 * performance contract kill threshold (50 ms), acting as an early-warning fence.
 *
 * @since 0.5.0
 * @see JfrAllocationMonitor
 * @see AbstractSubsystemZeroAllocTck
 */
public final class JfrPinningMonitor {

    private static final String VT_PINNED_EVENT      = "jdk.VirtualThreadPinned";
    public  static final long   DEFAULT_THRESHOLD_MS = 20L;
    private static final DateTimeFormatter TS_FMT    =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private JfrPinningMonitor() {}

    // =========================================================================
    // Config / Result / Event — Valhalla-ready records
    // =========================================================================

    /**
     * @param label       human-readable label used in the JFR file name
     * @param thresholdMs minimum duration (ms) for a pinning event to be captured
     */
    public record Config(String label, long thresholdMs) {
        public static Config defaults(String label) {
            return new Config(label, DEFAULT_THRESHOLD_MS);
        }
    }

    /**
     * @param durationMs how long the carrier was pinned
     * @param threadName virtual thread that caused the pin
     * @param stackTrace top-10 frames formatted as {@code "Class.method() | ..."}
     */
    public record PinnedEvent(double durationMs, String threadName, String stackTrace) {}

    /**
     * @param pinnedEvents events exceeding the threshold
     * @param jfrPath      path to the raw JFR file for post-mortem analysis
     * @param thresholdMs  threshold used during capture
     */
    public record Result(List<PinnedEvent> pinnedEvents, Path jfrPath, long thresholdMs) {
        public boolean hasPinning()  { return !pinnedEvents.isEmpty(); }
        public int     pinnedCount() { return pinnedEvents.size(); }
    }

    /** Workload executed inside the JFR recording window. */
    @FunctionalInterface
    public interface Workload {
        void execute() throws Exception;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Runs {@code workload} under JFR, captures {@code jdk.VirtualThreadPinned} events,
     * and returns a {@link Result}.
     */
    public static Result measure(Config config, Workload workload) throws Exception {
        Path jfrFile = buildJfrPath(config.label());
        try (Recording rec = new Recording()) {
            rec.enable(VT_PINNED_EVENT)
               .withThreshold(Duration.ofMillis(config.thresholdMs()))
               .withStackTrace();
            rec.setDestination(jfrFile);
            rec.start();
            workload.execute();
            rec.stop();
        }
        return parseResult(jfrFile, config.thresholdMs());
    }

    /**
     * Asserts zero pinning events. Fails with a formatted diagnostic if any detected.
     */
    public static void assertNoPinning(Result result, String label) {
        if (!result.hasPinning()) return;
        StringBuilder sb = new StringBuilder(512);
        sb.append("\n╔══════════════════════════════════════════════════════╗\n");
        sb.append(  "║  CARRIER PINNING TCK — VERDICT: GUILTY               ║\n");
        sb.append(  "╠══════════════════════════════════════════════════════╣\n");
        sb.append(  "║  Label        : ").append(pad(label, 38)).append(" ║\n");
        sb.append(  "║  Threshold    : ").append(pad(result.thresholdMs() + " ms", 38)).append(" ║\n");
        sb.append(  "║  Pinned Count : ").append(pad(String.valueOf(result.pinnedCount()), 38)).append(" ║\n");
        sb.append(  "║  JFR File     : ").append(pad(result.jfrPath().getFileName().toString(), 38)).append(" ║\n");
        sb.append(  "╠══════════════════════════════════════════════════════╣\n");
        result.pinnedEvents().stream().limit(5).forEach(e ->
            sb.append("  ▸ ").append(e.threadName())
              .append(" | ").append(String.format("%.2f", e.durationMs())).append(" ms\n")
              .append("    ").append(e.stackTrace()).append("\n")
        );
        sb.append("╚══════════════════════════════════════════════════════╝\n");
        sb.append("Per performance-contract.md: carrier blocked > ")
          .append(result.thresholdMs())
          .append(" ms is BANNED. Avoid synchronized, blocking I/O, non-VT-safe executors.");
        fail(sb.toString());
    }


    private static Result parseResult(Path jfrFile, long thresholdMs) throws IOException {
        List<PinnedEvent> events = new ArrayList<>();
        if (Files.exists(jfrFile)) {
            try (RecordingFile rf = new RecordingFile(jfrFile)) {
                while (rf.hasMoreEvents()) {
                    RecordedEvent ev = rf.readEvent();
                    if (!VT_PINNED_EVENT.equals(ev.getEventType().getName())) continue;
                    double ms = ev.getDuration().toNanos() / 1_000_000.0;
                    if (ms < thresholdMs) continue;
                    String thread = ev.getThread() != null ? ev.getThread().getJavaName() : "<unknown>";
                    events.add(new PinnedEvent(ms, thread, formatStack(ev)));
                }
            }
        }
        return new Result(List.copyOf(events), jfrFile, thresholdMs);
    }

    private static String formatStack(RecordedEvent ev) {
        if (ev.getStackTrace() == null) return "<no stack>";
        StringBuilder sb = new StringBuilder();
        ev.getStackTrace().getFrames().stream().limit(10).forEach(f ->
            sb.append(f.getMethod().getType().getName())
              .append(".").append(f.getMethod().getName()).append("() | ")
        );
        return sb.length() > 3 ? sb.substring(0, sb.length() - 3) : "<empty>";
    }

    private static Path buildJfrPath(String label) throws IOException {
        Path dir = Path.of("target", "jfr-reports", "pinning");
        Files.createDirectories(dir);
        String ts = LocalDateTime.now().format(TS_FMT);
        return dir.resolve("pin-" + label.toLowerCase(java.util.Locale.ROOT).replace(' ', '-') + "-" + ts + ".jfr");
    }

    private static String pad(String s, int w) {
        return s.length() >= w ? s.substring(0, w) : s + " ".repeat(w - s.length());
    }
}

