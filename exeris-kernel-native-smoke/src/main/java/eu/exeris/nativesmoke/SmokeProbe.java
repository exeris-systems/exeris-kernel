/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.nativesmoke;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * Custom JFR application event verifying event publication and read-back without duration fields.
 */
@Name("eu.exeris.nativesmoke.SmokeProbe")
@Label("Smoke Probe")
@Category({"Exeris Native Smoke"})
@StackTrace(false)
public final class SmokeProbe extends Event {

    /** Identifier for the probe emission. */
    @Label("Probe ID")
    public String probeId;

    /** Status message payload. */
    @Label("Message")
    public String message;

    /**
     * Records and commits this probe event.
     *
     * @param identifier probe identifier
     * @param statusMessage message payload
     */
    public void record(String identifier, String statusMessage) {
        this.probeId = identifier;
        this.message = statusMessage;
        commit();
    }
}
