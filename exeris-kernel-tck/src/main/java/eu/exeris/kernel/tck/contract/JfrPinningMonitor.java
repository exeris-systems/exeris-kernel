/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
import java.util.Locale;

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
 * <p><b>Not every pin over the fence is a blocked carrier.</b> Class loading and class
 * initialisation pin one too, for a reason no subsystem under test controls and for a duration set
 * by how busy the host is — {@link CarrierPinClassification} states the mechanism and the
 * measurement. Those events are captured and reported in {@link Result#classInitEvents()} but are
 * not counted by {@link Result#hasPinning()} or {@link #assertNoPinning}; everything else is,
 * including a pin the JVM declines to explain.
 *
 * @since 0.5
 * @see JfrAllocationMonitor
 * @see AbstractSubsystemZeroAllocTck
 */
public final class JfrPinningMonitor {

    private static final System.Logger LOG = System.getLogger(JfrPinningMonitor.class.getName());

    private static final String VT_PINNED_EVENT = CarrierPinClassification.VT_PINNED_EVENT;

    /** Default carrier-pinning threshold, in milliseconds; see the class-level contract note. */
    public static final long DEFAULT_THRESHOLD_MS = 20L;
    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private JfrPinningMonitor() {
    }

    // =========================================================================
    // Config / Result / Event — Valhalla-ready records
    // =========================================================================

    /**
     * Capture configuration for one {@link #measure} run.
     *
     * @param label       human-readable label used in the JFR file name
     * @param thresholdMs minimum duration (ms) for a pinning event to be captured
     */
    public record Config(String label, long thresholdMs) {
        /**
         * A config using {@link #DEFAULT_THRESHOLD_MS} as the capture threshold.
         *
         * @param label human-readable label used in the JFR file name
         * @return a config capturing events at or above {@link #DEFAULT_THRESHOLD_MS} ms
         */
        public static Config defaults(String label) {
            return new Config(label, DEFAULT_THRESHOLD_MS);
        }
    }

    /**
     * One captured {@code jdk.VirtualThreadPinned} event that met the capture threshold.
     *
     * @param durationMs   how long the carrier was pinned
     * @param threadName   virtual thread that caused the pin
     * @param stackTrace   top-10 frames formatted as {@code "Class.method() | ..."}
     * @param pinnedReason the JVM's own account of the pin, from the event's {@code pinnedReason}
     *                     field; a JDK without that field says so rather than inventing one
     * @param classInit    whether this pin is class loading or class initialisation rather than a
     *                     blocked carrier — see {@link CarrierPinClassification}
     */
    public record PinnedEvent(double durationMs, String threadName, String stackTrace,
                              String pinnedReason, boolean classInit) {

        /**
         * Creates an event without a recorded reason or verdict.
         *
         * <p>This is the constructor this record carried before {@code pinnedReason} and
         * {@code classInit} were added, kept so a binding compiled against the earlier TCK artifact
         * still compiles and links. What it cannot preserve is a deconstruction pattern, which names
         * every component by position — the canonical constructor genuinely has two more.
         *
         * @param durationMs how long the carrier was pinned
         * @param threadName virtual thread that caused the pin
         * @param stackTrace top-10 frames formatted as {@code "Class.method() | ..."}
         */
        public PinnedEvent(double durationMs, String threadName, String stackTrace) {
            this(durationMs, threadName, stackTrace, "<unknown>", false);
        }
    }

    /**
     * Outcome of one {@link #measure} run.
     *
     * @param pinnedEvents    events exceeding the threshold that are counted against it — every pin
     *                        that is not class loading or class initialisation
     * @param classInitEvents events exceeding the threshold that are class loading or class
     *                        initialisation: reported, never counted
     * @param jfrPath         path to the raw JFR file for post-mortem analysis
     * @param thresholdMs     threshold used during capture
     */
    public record Result(List<PinnedEvent> pinnedEvents, List<PinnedEvent> classInitEvents,
                         Path jfrPath, long thresholdMs) {

        /**
         * Creates a result with no set-aside events.
         *
         * <p>The constructor this record carried before {@code classInitEvents} was added, kept for
         * the same reason as {@link PinnedEvent}'s: a binding compiled against the earlier artifact
         * keeps compiling and linking.
         *
         * @param pinnedEvents events exceeding the threshold that are counted against it
         * @param jfrPath      path to the raw JFR file for post-mortem analysis
         * @param thresholdMs  threshold used during capture
         */
        public Result(List<PinnedEvent> pinnedEvents, Path jfrPath, long thresholdMs) {
            this(pinnedEvents, List.of(), jfrPath, thresholdMs);
        }

        /**
         * Whether any pin met the capture threshold and is counted against it.
         *
         * <p>Class loading and class initialisation are not counted — they are in
         * {@link #classInitEvents()} — so this can be {@code false} on a run that recorded pins.
         * {@link CarrierPinClassification} states why.
         *
         * @return {@code true} if {@link #pinnedEvents} is non-empty
         */
        public boolean hasPinning() {
            return !pinnedEvents.isEmpty();
        }

        /**
         * The number of pins counted against the threshold, set-aside ones excluded.
         *
         * @return {@link #pinnedEvents}{@code .size()}
         */
        public int pinnedCount() {
            return pinnedEvents.size();
        }
    }

    /**
     * Workload executed inside the JFR recording window.
     */
    @FunctionalInterface
    public interface Workload {
        /**
         * Runs the workload once, under the active JFR recording.
         *
         * @throws Exception whatever the workload throws; it is not caught by {@link #measure}
         */
        void execute() throws Exception;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Runs {@code workload} under JFR, captures {@code jdk.VirtualThreadPinned} events,
     * and returns a {@link Result}.
     *
     * @param config   capture configuration
     * @param workload the code to run under the recording
     * @return the pinning events captured at or above {@code config}'s threshold
     * @throws Exception whatever {@code workload} throws, propagated unchanged
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
     *
     * @param result the outcome of a {@link #measure} run
     * @param label  human-readable label for the diagnostic on failure
     */
    public static void assertNoPinning(Result result, String label) {
        // Reported before the early return, and that ordering is the point: a fence that sets
        // evidence aside and then says nothing on a green run is a fence nobody can audit. This is
        // the only trace that the classification did anything at all.
        reportSetAside(result, label);
        if (!result.hasPinning()) return;
        // 1024, not 512: the report now carries each pin's reason and a set-aside block, and the
        // fixed frame alone is past 587 characters — PMD measured it.
        StringBuilder sb = new StringBuilder(1024);
        sb.append("\n╔══════════════════════════════════════════════════════╗\n");
        sb.append("║  CARRIER PINNING TCK — VERDICT: GUILTY               ║\n");
        sb.append("╠══════════════════════════════════════════════════════╣\n");
        sb.append("║  Label        : ").append(pad(label, 38)).append(" ║\n");
        sb.append("║  Threshold    : ").append(pad(result.thresholdMs() + " ms", 38)).append(" ║\n");
        sb.append("║  Pinned Count : ").append(pad(String.valueOf(result.pinnedCount()), 38)).append(" ║\n");
        sb.append("║  JFR File     : ").append(pad(result.jfrPath().getFileName().toString(), 38)).append(" ║\n");
        sb.append("╠══════════════════════════════════════════════════════╣\n");
        result.pinnedEvents().stream().limit(5).forEach(e ->
                sb.append("  ▸ ").append(e.threadName())
                        .append(" | ").append(String.format(java.util.Locale.ROOT, "%.2f", e.durationMs())).append(" ms")
                        .append(" | ").append(e.pinnedReason()).append("\n")
                        .append("    ").append(e.stackTrace()).append("\n")
        );
        sb.append("╚══════════════════════════════════════════════════════╝\n");

        sb.append("Per performance-contract.md: carrier blocked > ")
                .append(result.thresholdMs())
                .append(" ms is BANNED. Avoid synchronized, blocking I/O, non-VT-safe executors.");
        throw new AssertionError(sb.toString());
    }


    /**
     * Names the pins the classification set aside, on every run rather than only a failing one.
     *
     * @param result the outcome of a {@link #measure} run
     * @param label  human-readable label for the diagnostic
     */
    private static void reportSetAside(Result result, String label) {
        if (result.classInitEvents().isEmpty() || !LOG.isLoggable(System.Logger.Level.INFO)) {
            return;
        }
        StringBuilder sb = new StringBuilder(256)
                .append("[").append(label).append("] ")
                .append(result.classInitEvents().size())
                .append(" carrier pin(s) over ").append(result.thresholdMs())
                .append(" ms were class loading or class initialisation and are NOT counted against the fence");
        result.classInitEvents().stream().limit(5).forEach(e ->
                sb.append(System.lineSeparator())
                        .append("  · ").append(e.threadName())
                        .append(" | ").append(String.format(Locale.ROOT, "%.2f", e.durationMs()))
                        .append(" ms | ").append(e.pinnedReason()));
        LOG.log(System.Logger.Level.INFO, sb.toString());
    }

    private static Result parseResult(Path jfrFile, long thresholdMs) throws IOException {
        List<PinnedEvent> counted = new ArrayList<>();
        List<PinnedEvent> classInit = new ArrayList<>();
        if (Files.exists(jfrFile)) {
            try (RecordingFile rf = new RecordingFile(jfrFile)) {
                while (rf.hasMoreEvents()) {
                    RecordedEvent ev = rf.readEvent();
                    if (!VT_PINNED_EVENT.equals(ev.getEventType().getName())) continue;
                    double ms = ev.getDuration().toNanos() / 1_000_000.0;
                    if (ms < thresholdMs) continue;
                    String thread = ev.getThread() != null ? ev.getThread().getJavaName() : "<unknown>";
                    String reason = CarrierPinClassification.pinnedReason(ev);
                    boolean cold = CarrierPinClassification.isClassLoadingOrInit(
                            reason,
                            CarrierPinClassification.frames(
                                    ev.getStackTrace(), CarrierPinClassification.CLASS_WORK_FRAME_DEPTH));
                    PinnedEvent event = new PinnedEvent(ms, thread, formatStack(ev), reason, cold);
                    (cold ? classInit : counted).add(event);
                }
            }
        }
        return new Result(List.copyOf(counted), List.copyOf(classInit), jfrFile, thresholdMs);
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

    /**
     * Sanitizes a free-form label into a filesystem-safe token for use in JFR filenames.
     *
     * <p>Rules applied in order:
     * <ol>
     *   <li>Null input is replaced with {@code "label"}.</li>
     *   <li>Lowercased using {@link Locale#ROOT} (no Turkish-I surprises).</li>
     *   <li>Any character outside {@code [a-z0-9\-_]} is mapped to {@code '-'}.</li>
     *   <li>Consecutive {@code '-'} are collapsed to a single {@code '-'}.</li>
     *   <li>Leading and trailing {@code '-'} are trimmed.</li>
     *   <li>Result is capped at 64 characters.</li>
     *   <li>If the result is empty after all transformations, falls back to {@code "label"}.</li>
     * </ol>
     *
     * @param label raw label string; may be {@code null}
     * @return a non-null, non-empty filesystem-safe token
     */
    private static String sanitizeLabel(String label) {
        final int maxLength = 64;
        String normalized = (label == null ? "label" : label).toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(normalized.length());
        char prev = 0;
        for (int i = 0; i < normalized.length() && sb.length() < maxLength; i++) {
            char c = normalized.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_';
            if (!allowed) {
                c = '-';
            }
            if (c == '-' && prev == '-') {
                continue;
            }
            sb.append(c);
            prev = c;
        }
        int start = 0;
        int end = sb.length();
        while (start < end && sb.charAt(start) == '-') {
            start++;
        }
        while (end > start && sb.charAt(end - 1) == '-') {
            end--;
        }
        return (start < end) ? sb.substring(start, end) : "label";
    }

    private static Path buildJfrPath(String label) throws IOException {
        Path dir = Path.of("target", "jfr-reports", "pinning");
        Files.createDirectories(dir);
        String ts = LocalDateTime.now().format(TS_FMT);
        String safeLabel = sanitizeLabel(label);
        return dir.resolve("pin-" + safeLabel + "-" + ts + ".jfr");
    }

    private static String pad(String s, int w) {
        return s.length() >= w ? s.substring(0, w) : s + " ".repeat(w - s.length());
    }
}
