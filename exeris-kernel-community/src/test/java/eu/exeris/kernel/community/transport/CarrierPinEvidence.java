/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads {@code jdk.VirtualThreadPinned} events out of a recording and says, for each one, whether
 * the JVM was loading or initialising a class rather than the code under test blocking a carrier.
 *
 * <h2>Why the distinction is load-bearing</h2>
 * <p>A virtual thread that runs a {@code <clinit>} cannot unmount — the JVM reports the pin as
 * {@code "VM call to <class>.<clinit> on stack"} — and every virtual thread waiting on another
 * thread's initialisation of the same class blocks pinned too
 * ({@code "Waited for initialization of <class> by another thread"}). JEP 491 unpinned
 * {@code synchronized} and {@code Object.wait}; it did not unpin class initialisation. Those pins
 * say the JVM was cold, not that a carrier was blocked by the code under test, and their duration
 * scales with how contended the host is — which is how they cross a fixed millisecond fence on a
 * constrained runner and nowhere else.
 *
 * <p>Nothing here widens to "pins we would rather not see". A blocking syscall on a carrier pins
 * with a native frame on the stack and a different reason, and stays counted.
 *
 * @since 0.12
 */
final class CarrierPinEvidence {

    /** The JFR event this reads; the JVM emits it when a virtual thread blocks while pinned. */
    static final String VT_PINNED_EVENT = "jdk.VirtualThreadPinned";

    private static final int MAX_REPORTED_FRAMES = 4;

    private CarrierPinEvidence() {
        // Static helper — no instances.
    }

    /**
     * One recorded pin: what the JVM said about it, not only which thread it happened on.
     *
     * @param thread    the virtual thread that was pinned
     * @param millis    how long it was pinned
     * @param reason    the JVM's own {@code pinnedReason}
     * @param frames    the top frames of its stack, outermost first
     * @param classInit whether this pin is class loading or class initialisation
     */
    record Pin(String thread, double millis, String reason, List<String> frames, boolean classInit) {

        /**
         * Renders this pin for a failure message: thread, duration, the JVM's reason, then the top
         * frames. The reason and the stack are the diagnosis; the thread name alone is not.
         *
         * @return a multi-line description, indented for a report
         */
        String describe() {
            StringBuilder out = new StringBuilder(160)
                    .append(String.format(Locale.ROOT, "  - %s pinned %.2f ms — %s", thread, millis, reason));
            for (String frame : frames) {
                out.append(System.lineSeparator()).append("      ").append(frame);
            }
            return out.toString();
        }
    }

    /**
     * Reads every pinning event at or above {@code thresholdMs} from {@code jfr}.
     *
     * @param jfr         the recording; a missing file yields an empty list
     * @param thresholdMs the fence, in milliseconds
     * @return the pins found, in recording order
     * @throws IOException if the recording cannot be read
     */
    static List<Pin> read(Path jfr, long thresholdMs) throws IOException {
        List<Pin> pins = new ArrayList<>();
        if (!Files.exists(jfr)) {
            return pins;
        }
        try (RecordingFile recording = new RecordingFile(jfr)) {
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                if (!VT_PINNED_EVENT.equals(event.getEventType().getName())) {
                    continue;
                }
                double millis = event.getDuration().toNanos() / 1_000_000.0;
                if (millis < thresholdMs) {
                    continue;
                }
                String thread = event.getThread() != null ? event.getThread().getJavaName() : "<unknown>";
                String reason = pinnedReason(event);
                List<String> frames = frames(event.getStackTrace());
                List<String> top = frames.subList(0, Math.min(MAX_REPORTED_FRAMES, frames.size()));
                pins.add(new Pin(thread, millis, reason, top, isClassLoadingOrInit(reason, frames)));
            }
        }
        return pins;
    }

    /**
     * Whether a pin is the JVM loading or initialising a class.
     *
     * @param reason the JVM's {@code pinnedReason}; never {@code null}
     * @param frames the stack, as {@code Type.method} strings, outermost first
     * @return {@code true} if this pin is cold-start class work rather than a blocked carrier
     */
    static boolean isClassLoadingOrInit(String reason, List<String> frames) {
        if (reason.contains("Waited for initialization of") || reason.contains("<clinit>")) {
            return true;
        }
        for (String frame : frames) {
            if (frame.endsWith(".<clinit>")
                    || frame.startsWith("jdk.internal.loader.")
                    || frame.startsWith("java.lang.ClassLoader.loadClass")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Renders a list of pins, one block each.
     *
     * @param pins the pins to render
     * @return the rendered block, empty if {@code pins} is empty
     */
    static String render(List<Pin> pins) {
        StringBuilder out = new StringBuilder(256);
        for (Pin pin : pins) {
            if (out.length() > 0) {
                out.append(System.lineSeparator());
            }
            out.append(pin.describe());
        }
        return out.toString();
    }

    /**
     * Renders the pins that were classified as class loading or initialisation, so a failure report
     * says what was set aside and why rather than hiding it.
     *
     * @param classInit the pins not counted against the fence
     * @return a line, plus the rendered pins when there are any
     */
    static String renderSetAside(List<Pin> classInit) {
        if (classInit.isEmpty()) {
            return "No class-loading or class-initialisation pins in this run.";
        }
        return classInit.size() + " class-loading/initialisation pin(s), not counted against the fence:"
                + System.lineSeparator() + render(classInit);
    }

    /** {@code pinnedReason} carries the JVM's own account of the pin; the field exists from JDK 24. */
    private static String pinnedReason(RecordedEvent event) {
        if (!event.hasField("pinnedReason")) {
            return "<no pinnedReason field on this JDK>";
        }
        String reason = event.getString("pinnedReason");
        return reason == null ? "<unknown>" : reason;
    }

    private static List<String> frames(RecordedStackTrace stack) {
        if (stack == null) {
            return List.of();
        }
        List<String> frames = new ArrayList<>(stack.getFrames().size());
        for (RecordedFrame frame : stack.getFrames()) {
            frames.add(frame.getMethod().getType().getName() + "." + frame.getMethod().getName());
        }
        return frames;
    }
}
