/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.config.jfr;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
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
 * @since 0.5
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

        /** Name of the config file whose on-disk change triggered this reload. */
        @Label("File")
        @Description("Config file name that triggered the reload")
        public String file;

        /** Dot-path key of the {@code @Dynamic} field that was reloaded. */
        @Label("Key")
        @Description("Dot-path key of the reloaded field")
        public String key;

        /** Wall-clock duration of the reload callback invocation, in microseconds. */
        @Label("Duration (µs)")
        @Description("Wall-clock time from reload callback entry to VarHandle.setRelease() in microseconds")
        public long durationUs;
    /**
     * Creates an unrecorded event.
     *
     * <p>The emitter assigns the public fields and calls {@link Event#commit()}. An instance that is never
     * committed contributes nothing to a recording.
     */
    public DynamicFieldReloadedEvent() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

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

        /** Name of the config file whose on-disk change triggered the failed reload attempt. */
        @Label("File")
        @Description("Config file that failed to reload")
        public String file;

        /** Dot-path key of the {@code @Dynamic} field that failed to update. */
        @Label("Key")
        @Description("Dot-path key of the field that failed to update")
        public String key;

        /**
         * Static, secret-free failure description — never the attempted value. At the current call
         * sites this is always {@code "error type: " + <exception class simple name>}.
         */
        @Label("Reason")
        @Description("Static failure description (no secret data)")
        public String reason;
    /**
     * Creates an unrecorded event.
     *
     * <p>The emitter assigns the public fields and calls {@link Event#commit()}. An instance that is never
     * committed contributes nothing to a recording.
     */
    public DynamicReloadFailedEvent() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

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
        if (!FlightRecorder.isInitialized()) {
            return;
        }
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
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        DynamicReloadFailedEvent evt = new DynamicReloadFailedEvent();
        if (!evt.isEnabled()) {
            return;
        }
        evt.file   = file;
        evt.key    = key;
        evt.reason = reason;
        evt.commit();
    }

    /**
     * Emits a {@link DynamicReloadFailedEvent} for reload failures with error type.
     *
     * @param file      config file name
     * @param key       dot-path key
     * @param errorType exception class used to derive a static failure description
     */
    public static void emitFailed(String file, String key, Class<?> errorType) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        DynamicReloadFailedEvent evt = new DynamicReloadFailedEvent();
        if (!evt.isEnabled()) {
            return;
        }
        evt.file = file;
        evt.key = key;
        evt.reason = "error type: " + errorType.getSimpleName();
        evt.commit();
    }
}

