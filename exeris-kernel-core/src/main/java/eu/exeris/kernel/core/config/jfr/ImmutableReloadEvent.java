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
 * JFR telemetry for {@code @Immutable} sealed-key reload refusals (EX-CFG-1004).
 *
 * <p>Emitted by {@code DynamicConfigFileWatcher} when an on-disk change is observed for a
 * key marked {@code @Immutable}. This is a security-relevant audit signal — an operator (or
 * an attacker with write access to the config mount) attempted to mutate a sealed trust
 * anchor at runtime. The refusal carries the file and key name only; never the value.
 *
 * @since 0.9
 */
public final class ImmutableReloadEvent {

    private ImmutableReloadEvent() {}

    /**
     * Emitted when a reload of an {@code @Immutable} key is refused (EX-CFG-1004).
     * The sealed value is preserved; no field is mutated.
     */
    @Name("eu.exeris.kernel.config.ImmutableReloadRefused")
    @Label("Immutable Reload Refused")
    @Category({"Exeris", "Config"})
    @Description("Emitted when a runtime reload of an @Immutable config key is refused — EX-CFG-1004")
    @StackTrace(false)
    public static final class ImmutableReloadRefusedEvent extends Event {

        /** Name of the config file whose on-disk change attempted to mutate the sealed key. */
        @Label("File")
        @Description("Config file that carried the change to the sealed key")
        public String file;

        /** Dot-path key of the sealed {@code @Immutable} field the change targeted; never the value. */
        @Label("Key")
        @Description("Dot-path key of the sealed @Immutable field (never the value)")
        public String key;
    }

    /**
     * Emits an {@link ImmutableReloadRefusedEvent} — minimal overhead when JFR is disabled.
     *
     * @param file config file name (relative to config dir)
     * @param key  dot-path key of the sealed field (must NOT carry the value)
     */
    public static void emitRefused(String file, String key) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        ImmutableReloadRefusedEvent evt = new ImmutableReloadRefusedEvent();
        if (!evt.isEnabled()) {
            return;
        }
        evt.file = file;
        evt.key  = key;
        evt.commit();
    }
}
