/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.telemetry.jfr;

/**
 * A holder with a nested "event" class, standing in for the shape most of this kernel's nested JFR
 * events have: the emit helper is on the holder, so the holder is what the first emit initialises.
 *
 * <p>Top-level and outside the test class on purpose — a class nested in the test class has already
 * been initialised by the time a test runs, which would make the assertion vacuous.
 */
final class NestedWarmupProbe {

    static {
        WarmupProbeLog.record("NestedWarmupProbe");
    }

    private NestedWarmupProbe() {
        // Never instantiated — the static initialiser is the whole subject.
    }

    /** The nested stand-in for the event class a catalogue names. */
    static final class Inner {

        static {
            WarmupProbeLog.record("NestedWarmupProbe$Inner");
        }

        private Inner() {
            // Never instantiated — the static initialiser is the whole subject.
        }
    }
}
