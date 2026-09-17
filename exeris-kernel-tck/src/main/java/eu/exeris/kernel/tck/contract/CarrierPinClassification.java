/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;

import java.util.ArrayList;
import java.util.List;

/**
 * Tells a {@code jdk.VirtualThreadPinned} event that means a blocked carrier apart from one that
 * means a cold class.
 *
 * <h2>Why a pinning fence needs this</h2>
 * <p>Class loading and class <em>initialisation</em> pin a carrier, and not for any reason a
 * subsystem under test controls. A virtual thread running a {@code <clinit>} cannot unmount — the
 * JVM reports {@code "VM call to <class>.<clinit> on stack"} — and every virtual thread that reaches
 * the same class while it is initialising blocks in {@code Object.wait} inside class initialisation,
 * pinned as well ({@code "Waited for initialization of <class> by another thread"}). JEP 491
 * unpinned {@code synchronized} and {@code Object.wait}; it did not unpin class initialisation.
 *
 * <p>The duration of such a pin is a property of the host, not of the code: the waiters hold the
 * carriers the initialiser needs, so the stall grows with contention. Measured on this repository's
 * transport suite under the two-carrier model, on one class: 0.4–1 ms per pin on an idle 12-core
 * host, 15–16 ms on the same host under CPU pressure, past 20 ms on a constrained runner. A fixed
 * millisecond fence that counts these fails on a busy runner and passes everywhere else, which is
 * the shape of a flake, and it reports a defect that is not there.
 *
 * <h2>What stays counted</h2>
 * <p>Only class loading and class initialisation are set aside, and only on the JVM's own account
 * of the pin — its {@code pinnedReason} — or on a class-loader or {@code <clinit>} frame in the
 * stack. A blocking syscall on a carrier, which is what a carrier-pinning TCK exists to catch, pins
 * with a native frame and a different reason, and stays counted. A pin the JVM does not explain
 * stays counted too: an unknown reason is not evidence of a benign one.
 *
 * @since 0.12
 * @see JfrPinningMonitor
 */
public final class CarrierPinClassification {

    /** The JFR event the JVM emits when a virtual thread blocks while pinned to its carrier. */
    public static final String VT_PINNED_EVENT = "jdk.VirtualThreadPinned";

    /**
     * The types whose frames mean the JVM is loading a class.
     *
     * <p>An enumerated set, not the {@code jdk.internal.loader.} package prefix this once matched on.
     * That package also holds {@code NativeLibraries}, which is where a carrier blocks while a native
     * library is being loaded — a real block, on a kernel that loads OpenSSL through FFM, and the
     * prefix would have filed it as benign class loading on every fence.
     */
    private static final List<String> CLASS_LOADING_TYPES = List.of(
            "java.lang.ClassLoader.loadClass",
            "java.lang.ClassLoader.defineClass",
            "jdk.internal.loader.BuiltinClassLoader.",
            "jdk.internal.loader.ClassLoaders",
            "jdk.internal.loader.URLClassPath");

    private static final String NO_REASON_FIELD = "<no pinnedReason field on this JDK>";
    private static final String UNKNOWN_REASON = "<unknown>";

    private CarrierPinClassification() {
        // Static helper — no instances.
    }

    /**
     * The JVM's own account of why the carrier was pinned.
     *
     * <p>{@code pinnedReason} exists from JDK 24. On a JDK without it the return value says so
     * rather than inventing one, and every such pin then stays counted.
     *
     * @param event a recorded {@code jdk.VirtualThreadPinned} event; must not be {@code null}
     * @return the reason string, never {@code null}
     */
    public static String pinnedReason(RecordedEvent event) {
        if (!event.hasField("pinnedReason")) {
            return NO_REASON_FIELD;
        }
        String reason = event.getString("pinnedReason");
        return reason == null ? UNKNOWN_REASON : reason;
    }

    /**
     * The event's stack as {@code Type.method} strings, outermost frame first.
     *
     * @param stack the recorded stack; may be {@code null}
     * @return the frames, empty if the event carried no stack
     */
    public static List<String> frames(RecordedStackTrace stack) {
        if (stack == null) {
            return List.of();
        }
        List<String> frames = new ArrayList<>(stack.getFrames().size());
        for (RecordedFrame frame : stack.getFrames()) {
            frames.add(frame.getMethod().getType().getName() + "." + frame.getMethod().getName());
        }
        return frames;
    }

    private static boolean isClassLoadingFrame(String frame) {
        for (String type : CLASS_LOADING_TYPES) {
            if (frame.startsWith(type)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a pin described by {@code reason} and {@code frames} is class loading or class
     * initialisation.
     *
     * @param reason the JVM's {@code pinnedReason}; must not be {@code null}
     * @param frames the stack as {@code Type.method} strings; must not be {@code null}
     * @return {@code true} if the pin is cold-start class work rather than a blocked carrier
     */
    public static boolean isClassLoadingOrInit(String reason, List<String> frames) {
        if (reason.contains("Waited for initialization of") || reason.contains("<clinit>")) {
            return true;
        }
        for (String frame : frames) {
            if (frame.endsWith(".<clinit>") || isClassLoadingFrame(frame)) {
                return true;
            }
        }
        return false;
    }
}
