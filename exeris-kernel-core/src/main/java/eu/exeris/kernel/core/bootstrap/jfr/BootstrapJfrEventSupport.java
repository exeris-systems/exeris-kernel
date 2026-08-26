/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.bootstrap.jfr;

import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;

/* default */ final class BootstrapJfrEventSupport {

    private BootstrapJfrEventSupport() {
    }

    /* default */ static <E extends Event> E beginIfEnabled(E event) {
        if (!FlightRecorder.isInitialized() || !event.isEnabled()) {
            return null;
        }
        event.begin();
        return event;
    }
}