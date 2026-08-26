/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.crypto.tls;

import eu.exeris.kernel.spi.crypto.TlsPhase;
import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * JFR event emitted on every successful {@link TlsStateMachine} phase transition.
 *
 * <h2>JFR-First Contract</h2>
 * <p>Zero overhead when JFR is not recording ({@link #isEnabled()} guard).
 * No allocation on the hot path — phase names are enum constants, not strings.
 *
 * @since 0.5.0
 */
@Name("eu.exeris.kernel.tls.PhaseTransition")
@Label("TLS Phase Transition")
@Description("Emitted when a TLS session state machine successfully transitions between phases")
@Category({"Exeris Kernel", "TLS"})
@StackTrace(false)
final class TlsPhaseTransitionEvent extends Event {

    /* default */ @Label("From Phase") String fromPhase;
    /* default */ @Label("To Phase")   String toPhase;

    /* default */ static void emit(TlsPhase from, TlsPhase targetPhase) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        TlsPhaseTransitionEvent event = new TlsPhaseTransitionEvent();
        if (event.isEnabled()) {
            event.fromPhase = from.name();
            event.toPhase   = targetPhase.name();
            event.commit();
        }
    }
}
