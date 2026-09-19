/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.telemetry.jfr;

/**
 * A top-level stand-in for an event class that declares its own emit helper.
 *
 * <p>Its presence is what keeps the enclosing-class walk from over-reaching: warming this name must
 * initialise this class and nothing else.
 */
final class TopLevelWarmupProbe {

    static {
        WarmupProbeLog.record("TopLevelWarmupProbe");
    }

    private TopLevelWarmupProbe() {
        // Never instantiated — the static initialiser is the whole subject.
    }
}
