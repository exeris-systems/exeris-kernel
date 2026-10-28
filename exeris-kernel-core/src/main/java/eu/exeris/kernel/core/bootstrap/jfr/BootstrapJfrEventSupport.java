/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.bootstrap.jfr;

import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;

/**
 * Shared {@code begin()}-if-enabled guard for the kernel's "provider selected" JFR events (the
 * events, flow and graph subsystems each have one), exposed to callers through
 * {@link BootstrapJfrEvents#beginIfEnabled(Event)}.
 */
/* default */ final class BootstrapJfrEventSupport {

    private BootstrapJfrEventSupport() {
    }

    /**
     * Begins timing {@code event} and returns it, or returns {@code null} when Flight Recorder is
     * not initialized or the event type is disabled — the caller's signal to skip populating
     * fields and committing.
     *
     * @param event the newly constructed event to begin timing
     * @param <E>   the JFR event type
     * @return {@code event} after {@link Event#begin()}, or {@code null} when recording is inactive
     */
    /* default */ static <E extends Event> E beginIfEnabled(E event) {
        if (!FlightRecorder.isInitialized() || !event.isEnabled()) {
            return null;
        }
        event.begin();
        return event;
    }

    /**
     * Returns {@code event} when Flight Recorder is initialized and the event type is enabled, and
     * {@code null} otherwise. Unlike {@link #beginIfEnabled(Event)} it does not begin the event: the caller
     * populates the fields and commits once, which is the single-phase form.
     *
     * @param event the newly constructed event to check
     * @param <E>   the JFR event type
     * @return {@code event}, or {@code null} when it would not be recorded
     */
    /* default */ static <E extends Event> E enabledOrNull(E event) {
        return FlightRecorder.isInitialized() && event.isEnabled() ? event : null;
    }
}