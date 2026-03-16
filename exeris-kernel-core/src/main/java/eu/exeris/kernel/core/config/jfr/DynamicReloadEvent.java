/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.config.jfr;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * JFR telemetry for {@code @Dynamic} field hot-reload lifecycle.
 *
 * <p>Emitted by {@code DynamicConfigFileWatcher} on every file-change event.
 * Minimal overhead when JFR recording is disabled — the {@code isEnabled()} guard
 * in the emit helper short-circuits before any field assignment.
 *
 * @since 0.5.0
 */
public final class DynamicReloadEvent {

    private DynamicReloadEvent() {}

    // =========================================================================
    // Event: DynamicFieldReloaded
    // =========================================================================

    /**
     * Emitted when a {@code @Dynamic} field is successfully reloaded from disk.
     */
    @Name("eu.exeris.kernel.config.DynamicFieldReloaded")
    @Label("Dynamic Field Reloaded")
    @Category({"Exeris", "Config"})
    @Description("Emitted when a @Dynamic config field is atomically updated from a file change")
    @StackTrace(false)
    public static final class DynamicFieldReloadedEvent extends Event {

        @Label("File")
        @Description("Config file name that triggered the reload")
        public String file;

        @Label("Key")
        @Description("Dot-path key of the reloaded field")
        public String key;

        @Label("Duration (µs)")
        @Description("Wall-clock time from WatchKey poll to VarHandle.setRelease() in microseconds")
        public long durationUs;
    }

    // =========================================================================
    // Event: DynamicReloadFailed
    // =========================================================================

    /**
     * Emitted when a {@code @Dynamic} reload fails (EX-CFG-1003).
     * The callback is not invoked; the previous stable value is kept.
     */
    @Name("eu.exeris.kernel.config.DynamicReloadFailed")
    @Label("Dynamic Reload Failed")
    @Category({"Exeris", "Config"})
    @Description("Emitted when a @Dynamic config reload fails — EX-CFG-1003")
    @StackTrace(false)
    public static final class DynamicReloadFailedEvent extends Event {

        @Label("File")
        @Description("Config file that failed to reload")
        public String file;

        @Label("Key")
        @Description("Dot-path key of the field that failed to update")
        public String key;

        @Label("Reason")
        @Description("Static failure description (no secret data)")
        public String reason;
    }

    // =========================================================================
    // Emit helpers
    // =========================================================================

    /**
     * Emits a {@link DynamicFieldReloadedEvent} — minimal overhead when JFR is disabled.
     *
     * @param file       config file name
     * @param key        dot-path key
     * @param startNanos {@code System.nanoTime()} captured before the reload started
     */
    public static void emitReloaded(String file, String key, long startNanos) {
        DynamicFieldReloadedEvent evt = new DynamicFieldReloadedEvent();
        if (!evt.isEnabled()) {
            return;
        }
        evt.file       = file;
        evt.key        = key;
        evt.durationUs = (System.nanoTime() - startNanos) / 1_000;
        evt.commit();
    }

    /**
     * Emits a {@link DynamicReloadFailedEvent} — minimal overhead when JFR is disabled.
     *
     * @param file   config file name
     * @param key    dot-path key
     * @param reason static failure description (must NOT contain secret data)
     */
    public static void emitFailed(String file, String key, String reason) {
        DynamicReloadFailedEvent evt = new DynamicReloadFailedEvent();
        if (!evt.isEnabled()) {
            return;
        }
        evt.file   = file;
        evt.key    = key;
        evt.reason = reason;
        evt.commit();
    }
}

