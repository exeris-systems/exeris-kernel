/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.telemetry.jfr;

import java.util.List;

/**
 * Initialises JFR event classes on the thread that starts a subsystem, so that no virtual thread
 * pays for their class initialisation on a hot path.
 *
 * <h2>Why an event class must not initialise on a virtual thread</h2>
 * <p>A {@link jdk.jfr.Event} subclass is registered with the JFR metadata repository from its own
 * static initialiser, and the class has to be loaded from the jar before that. A virtual thread
 * running a {@code <clinit>} <strong>cannot unmount</strong> — the JVM reports the pin as
 * {@code "VM call to <class>.<clinit> on stack"} — and every other virtual thread that reaches the
 * same class in that window blocks in {@code Object.wait} inside class initialisation, pinned as
 * well ({@code "Waited for initialization of <class> by another thread"}). JEP 491 unpinned
 * {@code synchronized} and {@code Object.wait}; it did not unpin class initialisation, so under
 * scarce carriers the waiters hold the carriers the initialiser needs.
 *
 * <p>Every emit site in this kernel is a static method <em>on the event class itself</em>
 * ({@code SomeEvent.emit(...)}), so the {@code FlightRecorder.isInitialized()} guard inside that
 * method cannot prevent the class initialisation — the call has already triggered it.
 * <strong>Turning JFR off does not avoid this cost</strong>, and it does not make the warm-up
 * pointless: measured over 103 event classes, initialisation took 79 ms with a recording running and
 * 108 ms without one. What dominates is the class load, not the JFR registration.
 *
 * <h2>Names, not class literals</h2>
 * <p>The catalogues that call this hold fully-qualified names. Two thirds of this kernel's event
 * classes are package-private, so a catalogue in any one package cannot name them with a class
 * literal, and widening 66 classes to {@code public} to hold a warm-up list would be a worse change
 * than carrying strings. A name that no longer resolves is a defect this cannot see — the catalogue
 * guard test is what sees it, by resolving every name and matching the set against the event classes
 * actually present.
 *
 * @since 0.12
 */
public final class JfrEventWarmup {

    private static final System.Logger LOG = System.getLogger(JfrEventWarmup.class.getName());

    private JfrEventWarmup() {
        // Static helper — no instances.
    }

    /**
     * Loads and initialises each named class, on the calling thread.
     *
     * <p>Idempotent and cheap after the first call: initialising an already-initialised class is a
     * state check. A name that does not resolve is logged and skipped rather than failing the
     * caller — this is a warm-up, and a subsystem that starts is worth more than a perfect one that
     * does not. The guard test is what keeps the catalogue honest; this is only what runs.
     *
     * @param classNames fully-qualified names of {@link jdk.jfr.Event} subclasses; must not be
     *                   {@code null}
     * @param loader     the loader to resolve them through — the one belonging to the module that
     *                   declares them, not this one and not the thread context loader. A driver's
     *                   events ship in the driver's artifact, so resolving them through Core's
     *                   loader is a guess that happens to hold on a flat classpath and stops
     *                   holding the moment anything layers them. Must not be {@code null}
     */
    public static void ensureInitialised(List<String> classNames, ClassLoader loader) {
        for (String className : classNames) {
            try {
                Class.forName(className, true, loader);
            } catch (ClassNotFoundException | LinkageError e) {
                // Not fatal: the event still works, it simply initialises wherever its first emit
                // lands — which is the behaviour this class exists to improve on, not a new failure.
                if (LOG.isLoggable(System.Logger.Level.WARNING)) {
                    LOG.log(System.Logger.Level.WARNING,
                            "JFR event class named by a warm-up catalogue did not initialise: " + className, e);
                }
            }
        }
    }
}
