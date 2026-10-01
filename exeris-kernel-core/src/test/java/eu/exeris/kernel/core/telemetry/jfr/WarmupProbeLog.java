/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.telemetry.jfr;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records which probe initialisers have run, in the order they ran.
 *
 * <p>A third class on purpose: the assertion has to read the record without initialising the probe
 * it is asking about, and any field on the probe itself would be initialised by the read.
 */
final class WarmupProbeLog {

    private static final List<String> ENTRIES = new CopyOnWriteArrayList<>();

    private WarmupProbeLog() {
        // Static recorder — no instances.
    }

    /**
     * Records that a probe's static initialiser ran.
     *
     * @param name the probe's simple binary name
     */
    static void record(String name) {
        ENTRIES.add(name);
    }

    /**
     * The initialisers that have run so far, in order.
     *
     * @return the recorded names, newest last
     */
    static List<String> entries() {
        return List.copyOf(ENTRIES);
    }
}
