/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.bootstrap.jfr;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * JFR event emitted at the very beginning of the kernel bootstrap sequence.
 *
 * <h2>JFR Waterfall — L0 Contract</h2>
 * <p>Every production JVM running Exeris Kernel MUST emit this event as the
 * first event in the bootstrap waterfall. Post-mortem analysis of any JFR
 * recording begins here. The event is emitted <em>before</em> any
 * {@link BootstrapJfrEvents.ConfigSettingsResolvedEvent}
 * or subsystem events — giving a precise wall-clock anchor for total boot duration.
 *
 * <h2>Event Waterfall order</h2>
 * <pre>
 *   KernelStartEvent            (this — before anything else)
 *   ConfigSettingsResolvedEvent (after ConfigProvider resolution)
 *   SubsystemInitializedEvent × N
 *   SubsystemStartedEvent × N
 *   KernelBootReadyEvent        (all subsystems RUNNING)
 * </pre>
 *
 * @since 0.5
 * @see BootstrapJfrEvents
 */
@Name("eu.exeris.kernel.bootstrap.KernelStart")
@Label("Kernel Start")
@Description("Emitted at the very first line of KernelBootstrap.boot() — "
           + "before config resolution, before any subsystem is touched.")
@Category({"Exeris Kernel", "Bootstrap"})
@StackTrace(false)
public final class KernelStartEvent extends Event {

    /** Human-readable version of the exeris-kernel artifact. */
    @Label("Kernel Version")
    public String kernelVersion;

    /** Active failure policy: FAIL_FAST or DEGRADE. */
    @Label("Failure Policy")
    public String failurePolicy;

    /** Bootstrap selector description (e.g. "ALL" or "memory,telemetry"). */
    @Label("Selector")
    public String selector;

    /** JVM version string ({@link Runtime.Version#toString()}). */
    @Label("JVM Version")
    public String jvmVersion;

    /**
     * Emits a {@link KernelStartEvent} if JFR recording is active.
     *
     * @param kernelVersion  exeris-kernel artifact version (e.g. {@code "0.5.0"})
     * @param failurePolicy  failure policy name
     * @param selector       selector description
     */
    public static void emit(String kernelVersion,
                            String failurePolicy,
                            String selector) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        KernelStartEvent event = new KernelStartEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.kernelVersion  = kernelVersion;
        event.failurePolicy  = failurePolicy;
        event.selector       = selector;
        event.jvmVersion     = Runtime.version().toString();
        event.commit();
    }
}

