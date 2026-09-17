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
 * <p>And a class initialiser is not a licence. Loading a native library or making an FFM downcall is
 * ordinary work for a {@code <clinit>}, so a frame that means a carrier is really blocked vetoes the
 * set-aside before any of the above is consulted — otherwise the one block this kernel is most
 * likely to take, OpenSSL through FFM, would read as benign on every fence in the repository.
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
            "jdk.internal.loader.ClassLoaders.",
            "jdk.internal.loader.URLClassPath.");

    /**
     * The frames that mean a carrier is really blocked, whatever else is on the stack.
     *
     * <p>Checked before anything else, and it has to be: loading a native library or making a
     * downcall is work a static initialiser is a perfectly ordinary place to do. This kernel loads
     * OpenSSL through FFM, and a {@code <clinit>} frame sitting above that block would otherwise
     * file it as benign class work — which is not a narrower fence, it is no fence at all for the
     * one thing a carrier-pinning TCK exists to catch.
     */
    private static final List<String> BLOCKING_TYPES = List.of(
            "jdk.internal.loader.NativeLibraries",
            "java.lang.System.load",
            "java.lang.System.loadLibrary",
            "java.lang.Runtime.load",
            "java.lang.ClassLoader$NativeLibrary",
            "jdk.internal.foreign.");

    /** The JVM's own phrasing for a pin taken inside a class initialiser. */
    private static final String VM_CALL_PREFIX = "VM call to ";

    /** The tail of that phrasing; the class name sits between the two. */
    private static final String CLINIT_ON_STACK_SUFFIX = ".<clinit> on stack";

    /**
     * How deep into the stack a class-loading frame still describes the pin.
     *
     * <p>Public because the reader that builds the frame list needs to know how many of them this
     * class will look at; building more is the cost the bound exists to avoid.
     *
     * <p>The blocking site is at the top. Deeper frames are the caller's context, and a stack 256
     * deep almost always has a {@code loadClass} or a {@code <clinit>} somewhere in it — scanning
     * the whole of it let any such frame outrank a genuinely blocked carrier and set a real pin
     * aside. Four covers what the JVM actually reports for class work: the loader frames sit
     * innermost, above the application frame that touched the class.
     */
    public static final int CLASS_WORK_FRAME_DEPTH = 4;

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
     * The event's stack as {@code Type.method} strings, <strong>innermost frame first</strong> —
     * the blocking site at index 0, as {@link RecordedStackTrace#getFrames()} orders it. The depth
     * heuristic below depends on that order.
     *
     * @param stack the recorded stack; may be {@code null}
     * @return the frames, empty if the event carried no stack
     */
    public static List<String> frames(RecordedStackTrace stack) {
        return frames(stack, Integer.MAX_VALUE);
    }

    /**
     * The innermost {@code limit} frames, as {@code Type.method} strings.
     *
     * <p>The classification reads four; a recorded stack can be 256 deep, and building the other
     * 252 strings to throw them away is the whole of the cost. The unbounded form stays for a
     * report, which does want all of them.
     *
     * @param stack the recorded stack; may be {@code null}
     * @param limit how many frames to take from the top; must not be negative
     * @return the frames, empty if the event carried no stack
     */
    public static List<String> frames(RecordedStackTrace stack, int limit) {
        if (stack == null) {
            return List.of();
        }
        List<RecordedFrame> recorded = stack.getFrames();
        int take = Math.min(limit, recorded.size());
        List<String> frames = new ArrayList<>(take);
        for (int i = 0; i < take; i++) {
            RecordedFrame frame = recorded.get(i);
            frames.add(frame.getMethod().getType().getName() + "." + frame.getMethod().getName());
        }
        return frames;
    }

    private static boolean startsWithAny(String frame, List<String> types) {
        for (String type : types) {
            if (frame.startsWith(type)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code reason} is the JVM's account of a pin taken inside a class initialiser.
     *
     * <p>Matched on the shape the JVM actually emits, {@code "VM call to <class>.<clinit> on
     * stack"}, and not on {@code <clinit>} appearing anywhere in the string: a reason that merely
     * mentions a class initialiser is not a statement that the initialiser is what blocked.
     *
     * @param reason the JVM's {@code pinnedReason}
     * @return {@code true} if the reason names a class initialiser on the stack
     */
    private static boolean isClinitReason(String reason) {
        return reason.startsWith(VM_CALL_PREFIX) && reason.endsWith(CLINIT_ON_STACK_SUFFIX);
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
        int depth = Math.min(CLASS_WORK_FRAME_DEPTH, frames.size());
        // The veto runs first and over the same window. A static initialiser is an ordinary place to
        // load a native library or make a downcall, so "there is a <clinit> on the stack" cannot be
        // allowed to outrank "and it is blocked in NativeLibraries".
        for (int i = 0; i < depth; i++) {
            if (startsWithAny(frames.get(i), BLOCKING_TYPES)) {
                return false;
            }
        }
        if (reason.contains("Waited for initialization of") || isClinitReason(reason)) {
            return true;
        }
        for (int i = 0; i < depth; i++) {
            String frame = frames.get(i);
            if (frame.endsWith(".<clinit>") || startsWithAny(frame, CLASS_LOADING_TYPES)) {
                return true;
            }
        }
        return false;
    }
}
