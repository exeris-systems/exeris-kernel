/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.bootstrap.jfr;

import eu.exeris.kernel.spi.config.KernelProfile;
import eu.exeris.kernel.spi.exceptions.bootstrap.SubsystemCircularDependencyException;
import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

import java.util.Set;

/**
 * Container for the kernel bootstrap lifecycle's {@code jdk.jfr.Event} subclasses and their
 * emission helpers.
 *
 * <p>Most nested events fire from {@link eu.exeris.kernel.core.bootstrap.SubsystemOrchestrator}
 * during a single boot or shutdown pass. Two exceptions: {@link ConfigSettingsResolvedEvent}
 * fires earlier, from {@link eu.exeris.kernel.core.bootstrap.KernelBootstrap}, and
 * {@link SubsystemHealthTransitionEvent} fires later, from
 * {@link eu.exeris.kernel.core.bootstrap.health.KernelHealthMonitor} on a post-boot
 * {@code RUNNING}/{@code DEGRADED} flip. This class holds no state of its own beyond grouping the
 * events under one JFR category and is never instantiated.
 */
// UseExplicitTypes: 'var' is used for JFR event locals; explicit type would duplicate
// the inner class name on the same line, harming readability with zero type-safety gain.
@SuppressWarnings("PMD.UseExplicitTypes")
public final class BootstrapJfrEvents {

    private BootstrapJfrEvents() {}

    // =========================================================================
    // Event: SubsystemInitialized
    // =========================================================================

    /**
     * Emitted when a subsystem completes {@code initialize()}.
     * Useful for identifying which subsystem is the slowest to initialize.
     */
    @Name("eu.exeris.kernel.bootstrap.SubsystemInitialized")
    @Label("Subsystem Initialized")
    @Category({"Exeris Kernel", "Bootstrap"})
    @Description("Emitted when a kernel subsystem completes initialize()")
    @StackTrace(false)
    public static final class SubsystemInitializedEvent extends Event {

        /** Unique identifier of the subsystem that completed {@code initialize()}, e.g. {@code "memory"}. */
        @Label("Subsystem Name")
        @Description("Unique subsystem identifier (e.g., 'memory', 'persistence')")
        public String subsystemName;

        /** Wall-clock duration of the {@code initialize()} call, in milliseconds. */
        @Label("Duration (ms)")
        @Description("Wall-clock time for initialize() in milliseconds")
        public long durationMs;

        /** Kernel profile ({@code dev}/{@code test}/{@code prod}) active while the subsystem initialized. */
        @Label("Profile")
        @Description("Active kernel profile (dev/test/prod)")
        public KernelProfile profile;

        /**
         * Name of the bootstrap phase the subsystem belongs to: {@code FOUNDATION}, {@code SERVICES},
         * or {@code RUNTIME}.
         */
        @Label("Phase")
        @Description("Bootstrap phase: FOUNDATION, SERVICES, or RUNTIME")
        public String phase;

        /** {@code true} when {@code initialize()} returned normally; {@code false} when it threw. */
        @Label("Success")
        @Description("Whether initialize() completed without exception")
        public boolean success;

        /** The thrown exception's message when {@link #success} is {@code false}; the empty string otherwise. */
        @Label("Error Message")
        @Description("Exception message if success=false, empty otherwise")
        public String errorMessage = "";
    /**
     * Creates an unrecorded event.
     *
     * <p>The emitter assigns the public fields and calls {@link Event#commit()}. An instance that is never
     * committed contributes nothing to a recording.
     */
    public SubsystemInitializedEvent() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    }

    // =========================================================================
    // Event: SubsystemStarted
    // =========================================================================

    /**
     * Emitted when a subsystem completes {@code start()}.
     */
    @Name("eu.exeris.kernel.bootstrap.SubsystemStarted")
    @Label("Subsystem Started")
    @Category({"Exeris Kernel", "Bootstrap"})
    @StackTrace(false)
    public static final class SubsystemStartedEvent extends Event {

        /** Unique identifier of the subsystem that completed {@code start()}, e.g. {@code "memory"}. */
        @Label("Subsystem Name")
        public String subsystemName;

        /** Wall-clock duration of the {@code start()} call, in milliseconds. */
        @Label("Duration (ms)")
        public long durationMs;

        /**
         * Name of the bootstrap phase the subsystem belongs to: {@code FOUNDATION}, {@code SERVICES},
         * or {@code RUNTIME}.
         */
        @Label("Phase")
        @Description("Bootstrap phase: FOUNDATION, SERVICES, or RUNTIME")
        public String phase;
    /**
     * Creates an unrecorded event.
     *
     * <p>The emitter assigns the public fields and calls {@link Event#commit()}. An instance that is never
     * committed contributes nothing to a recording.
     */
    public SubsystemStartedEvent() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    }

    // =========================================================================
    // Event: SubsystemStopped
    // =========================================================================

    /**
     * Emitted when a subsystem completes {@code stop()} during graceful shutdown.
     */
    @Name("eu.exeris.kernel.bootstrap.SubsystemStopped")
    @Label("Subsystem Stopped")
    @Category({"Exeris Kernel", "Bootstrap"})
    @StackTrace(false)
    public static final class SubsystemStoppedEvent extends Event {

        /** Unique identifier of the subsystem that completed {@code stop()}, e.g. {@code "memory"}. */
        @Label("Subsystem Name")
        public String subsystemName;

        /** Wall-clock duration of the {@code stop()} call, in milliseconds. */
        @Label("Duration (ms)")
        public long durationMs;
    /**
     * Creates an unrecorded event.
     *
     * <p>The emitter assigns the public fields and calls {@link Event#commit()}. An instance that is never
     * committed contributes nothing to a recording.
     */
    public SubsystemStoppedEvent() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    }

    // =========================================================================
    // Event: SubsystemHealthTransition
    // =========================================================================

    /**
     * Emitted on a post-boot subsystem health transition involving {@code DEGRADED}
     * ({@code RUNNING ↔ DEGRADED}). Gives SREs a JFR trail for a flapping dependency that the
     * boot lifecycle events do not cover. Cold path (the health watcher's reconcile pass, not the
     * probe hot path), single-phase commit.
     */
    @Name("eu.exeris.kernel.bootstrap.SubsystemHealthTransition")
    @Label("Subsystem Health Transition")
    @Category({"Exeris Kernel", "Bootstrap"})
    @StackTrace(false)
    public static final class SubsystemHealthTransitionEvent extends Event {

        /** Identifier of the subsystem whose health state changed. */
        @Label("Subsystem Name")
        public String subsystemName;

        /** {@code SubsystemState} name the subsystem transitioned from, e.g. {@code "RUNNING"}. */
        @Label("From State")
        public String fromState;

        /** {@code SubsystemState} name the subsystem transitioned to, e.g. {@code "DEGRADED"}. */
        @Label("To State")
        public String toState;
    /**
     * Creates an unrecorded event.
     *
     * <p>The emitter assigns the public fields and calls {@link Event#commit()}. An instance that is never
     * committed contributes nothing to a recording.
     */
    public SubsystemHealthTransitionEvent() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    }

    // =========================================================================
    // Event: KernelBootReady
    // =========================================================================

    /**
     * Emitted when the entire kernel bootstrap completes — all subsystems RUNNING.
     *
     * <p>Compare {@code totalDurationMs} against the performance contract SLO:
     * prod boot ≤ 3000 ms, dev boot ≤ 1000 ms.
     */
    @Name("eu.exeris.kernel.bootstrap.KernelBootReady")
    @Label("Kernel Boot Ready")
    @Category({"Exeris Kernel", "Bootstrap"})
    @StackTrace(false)
    public static final class KernelBootReadyEvent extends Event {

        /** Wall-clock duration from boot start to every subsystem reaching {@code RUNNING}, in milliseconds. */
        @Label("Total Duration (ms)")
        public long totalDurationMs;

        /** Number of subsystems that reached {@code RUNNING} in this boot. */
        @Label("Subsystem Count")
        public int subsystemCount;

        /** Kernel profile ({@code dev}/{@code test}/{@code prod}) this boot resolved to. */
        @Label("Profile")
        public KernelProfile profile;

        /** Node identifier from the {@code exeris.node.id} system property, or {@code "local"} when unset. */
        @Label("Node ID")
        public String nodeId;

        /** String form of the bootstrap selector that chose this boot's subsystem set. */
        @Label("Selector")
        public String selector;
    /**
     * Creates an unrecorded event.
     *
     * <p>The emitter assigns the public fields and calls {@link Event#commit()}. An instance that is never
     * committed contributes nothing to a recording.
     */
    public KernelBootReadyEvent() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    }

    // =========================================================================
    // Event: KernelShutdownComplete
    // =========================================================================

    /**
     * Emitted when all subsystems have been stopped and the kernel has shut down cleanly.
     */
    @Name("eu.exeris.kernel.bootstrap.KernelShutdownComplete")
    @Label("Kernel Shutdown Complete")
    @Category({"Exeris Kernel", "Bootstrap"})
    @StackTrace(false)
    public static final class KernelShutdownCompleteEvent extends Event {

        /** Wall-clock duration from shutdown start to every subsystem being stopped, in milliseconds. */
        @Label("Total Duration (ms)")
        public long totalDurationMs;

        /**
         * Total number of subsystems the orchestrator tracked at shutdown, in reverse boot order.
         * Includes subsystems that were not running (skipped) and any whose {@code stop()} call
         * threw — a thrown {@code stop()} is logged and swallowed, not subtracted from this count.
         */
        @Label("Subsystem Count")
        @Description("Number of subsystems that were stopped")
        public int subsystemCount;
    /**
     * Creates an unrecorded event.
     *
     * <p>The emitter assigns the public fields and calls {@link Event#commit()}. An instance that is never
     * committed contributes nothing to a recording.
     */
    public KernelShutdownCompleteEvent() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    }

    // =========================================================================
    // Event: ConfigSettingsResolved
    // =========================================================================

    /**
     * Emitted when {@code ConfigProvider.kernelSettings()} is first accessed
     * (lazy initialization completes — exactly once per JVM lifetime).
     *
     * <p>Used to verify the {@code LazyConstant} semantic: resolved exactly once.
     */
    @Name("eu.exeris.kernel.bootstrap.ConfigSettingsResolved")
    @Label("Config Settings Resolved")
    @Category({"Exeris Kernel", "Bootstrap", "Config"})
    @StackTrace(false)
    public static final class ConfigSettingsResolvedEvent extends Event {

        /** {@code ConfigProvider.providerName()} of the {@code ConfigProvider} the ServiceLoader selected. */
        @Label("Provider Name")
        public String providerName;

        /** Resolved kernel profile as a string, e.g. {@code "prod"}. */
        @Label("Profile")
        public String profile;

        /**
         * Wall-clock time from resolving the provider to the lazy {@code kernelSettings()}
         * completing, in milliseconds.
         */
        @Label("Duration (ms)")
        public long durationMs;

        /** Where config was loaded from. Always {@code "serviceloader"} at the current single call site. */
        @Label("Source")
        @Description("Where config was loaded from: 'env', 'file', 'classpath'")
        public String source;
    /**
     * Creates an unrecorded event.
     *
     * <p>The emitter assigns the public fields and calls {@link Event#commit()}. An instance that is never
     * committed contributes nothing to a recording.
     */
    public ConfigSettingsResolvedEvent() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    }

    // =========================================================================
    // Event: CircularDependencyDetected
    // =========================================================================

    /**
     * Emitted immediately before the JVM is halted due to a circular dependency
     * in the subsystem graph (EX-BOOT-0001).
     *
     * <p>Useful for post-mortem analysis via JFR recording.
     */
    @Name("eu.exeris.kernel.bootstrap.CircularDependencyDetected")
    @Label("Circular Dependency Detected")
    @Category({"Exeris Kernel", "Bootstrap", "Fatal"})
    @StackTrace(true) // stack trace IS useful here — this is a fatal defect
    public static final class CircularDependencyDetectedEvent extends Event {

        /** Comma-and-space-joined subsystem names forming the dependency cycle, in cycle order. */
        @Label("Cycle Members")
        @Description("Comma-separated list of subsystem names forming the cycle")
        public String cycleMembers;

        /**
         * The {@code EX-BOOT-*} code identifying this fatal defect; currently always
         * {@link SubsystemCircularDependencyException#ERROR_CODE}.
         */
        @Label("Error Code")
        public String errorCode = SubsystemCircularDependencyException.ERROR_CODE;
    /**
     * Creates an unrecorded event.
     *
     * <p>The emitter assigns the public fields and calls {@link Event#commit()}. An instance that is never
     * committed contributes nothing to a recording.
     */
    public CircularDependencyDetectedEvent() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    }

    // =========================================================================
    // Static helpers
    // =========================================================================

    /**
     * Records a subsystem initialization event.
     *
     * @param name        subsystem name
     * @param startNanos  {@code System.nanoTime()} at start
     * @param profile     active kernel profile
     * @param phase       bootstrap phase name
     * @param success     whether initialization succeeded
     * @param error       exception message or empty string
     */
    @SuppressWarnings("PMD.LawOfDemeter") // JFR Event API mandates public field assignment — no setter API available
    public static void emitInitialized(String name, long startNanos,
                                       KernelProfile profile, String phase, boolean success, String error) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        var event = new SubsystemInitializedEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.subsystemName = name;
        event.durationMs    = (System.nanoTime() - startNanos) / 1_000_000;
        event.profile       = profile;
        event.phase         = phase;
        event.success       = success;
        event.errorMessage  = error == null ? "" : error;
        event.commit();
    }

    /**
     * Records a subsystem start event.
     *
     * @param name       subsystem name
     * @param startNanos start timestamp
     * @param phase      bootstrap phase name
     */
    public static void emitStarted(String name, long startNanos, String phase) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        var event = new SubsystemStartedEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.subsystemName = name;
        event.durationMs    = (System.nanoTime() - startNanos) / 1_000_000;
        event.phase         = phase;
        event.commit();
    }

    /**
     * Records a subsystem stop event.
     *
     * @param name       subsystem name
     * @param startNanos start timestamp
     */
    public static void emitStopped(String name, long startNanos) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        var event = new SubsystemStoppedEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.subsystemName = name;
        event.durationMs    = (System.nanoTime() - startNanos) / 1_000_000;
        event.commit();
    }

    /**
     * Records a post-boot subsystem health transition involving {@code DEGRADED}.
     *
     * @param name      subsystem name
     * @param fromState previous {@code SubsystemState} name
     * @param toState   new {@code SubsystemState} name
     */
    public static void emitHealthTransition(String name, String fromState, String toState) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        var event = new SubsystemHealthTransitionEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.subsystemName = name;
        event.fromState     = fromState;
        event.toState       = toState;
        event.commit();
    }

    /**
     * Records the kernel boot completion event.
     *
     * @param startNanos     boot start timestamp
     * @param subsystemCount number of running subsystems
     * @param profile        active kernel profile
     * @param nodeId         node identifier
     * @param selector       bootstrap selector description
     */
    @SuppressWarnings("PMD.LawOfDemeter") // JFR Event API mandates public field assignment — no setter API available
    public static void emitBootReady(long startNanos, int subsystemCount,
            KernelProfile profile, String nodeId, String selector) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        var event = new KernelBootReadyEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.totalDurationMs = (System.nanoTime() - startNanos) / 1_000_000;
        event.subsystemCount  = subsystemCount;
        event.profile         = profile;
        event.nodeId          = nodeId;
        event.selector        = selector;
        event.commit();
    }

    /**
     * Records kernel shutdown completion.
     *
     * @param startNanos     shutdown start timestamp
     * @param subsystemCount number of subsystems that were stopped
     */
    public static void emitShutdownComplete(long startNanos, int subsystemCount) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        var event = new KernelShutdownCompleteEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.totalDurationMs = (System.nanoTime() - startNanos) / 1_000_000;
        event.subsystemCount  = subsystemCount;
        event.commit();
    }

    /**
     * Records config settings resolution.
     *
     * @param providerName name of the winning ConfigProvider
     * @param profile      resolved kernel profile
     * @param startNanos   resolution start timestamp
     * @param source       source of config ({@code "env"}, {@code "file"}, {@code "classpath"})
     */
    public static void emitConfigResolved(String providerName, String profile,
            long startNanos, String source) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        var event = new ConfigSettingsResolvedEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.providerName = providerName;
        event.profile      = profile;
        event.durationMs   = (System.nanoTime() - startNanos) / 1_000_000;
        event.source       = source;
        event.commit();
    }

    /**
     * Records circular dependency detection and emits the JFR event.
     * Called immediately before throwing
     * {@link SubsystemCircularDependencyException}.
     *
     * @param cycleMembers ordered set of subsystem names forming the cycle
     */
    public static void emitCircularDependency(Set<String> cycleMembers) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        var event = new CircularDependencyDetectedEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.cycleMembers = String.join(", ", cycleMembers);
        event.commit();
    }

    /**
     * Begins timing {@code event} and returns it, or returns {@code null} when Flight Recorder is
     * not initialized or the event type is disabled.
     *
     * @param event the newly constructed event to begin timing
     * @param <E>   the JFR event type
     * @return {@code event} after {@link Event#begin()}, or {@code null} when recording is inactive
     */
    public static <E extends Event> E beginIfEnabled(E event) {
        return BootstrapJfrEventSupport.beginIfEnabled(event);
    }
}
