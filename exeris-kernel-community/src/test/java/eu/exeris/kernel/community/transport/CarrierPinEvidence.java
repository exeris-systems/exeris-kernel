/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.tck.contract.CarrierPinClassification;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads {@code jdk.VirtualThreadPinned} events out of a recording this test owns, and renders them
 * for a failure message.
 *
 * <p>The verdict on each event — blocked carrier, or the JVM loading and initialising a class —
 * comes from {@link CarrierPinClassification}, the same classifier
 * {@link eu.exeris.kernel.tck.contract.JfrPinningMonitor} uses, which states why the distinction
 * exists. What lives here is only what this test does differently: it drives its own
 * {@link jdk.jfr.Recording} around a window it controls, and it reports the reason and the top
 * frames rather than a thread name.
 *
 * @since 0.12
 */
final class CarrierPinEvidence {

    /** The JFR event this reads; the JVM emits it when a virtual thread blocks while pinned. */
    static final String VT_PINNED_EVENT = CarrierPinClassification.VT_PINNED_EVENT;

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
                String reason = CarrierPinClassification.pinnedReason(event);
                List<String> frames = CarrierPinClassification.frames(event.getStackTrace());
                List<String> top = frames.subList(0, Math.min(MAX_REPORTED_FRAMES, frames.size()));
                pins.add(new Pin(thread, millis, reason, top,
                        CarrierPinClassification.isClassLoadingOrInit(reason, frames)));
            }
        }
        return pins;
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
}
