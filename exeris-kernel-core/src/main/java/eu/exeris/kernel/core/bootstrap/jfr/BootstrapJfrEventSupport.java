/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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