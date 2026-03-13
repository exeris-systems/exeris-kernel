/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.bootstrap.jfr;

import eu.exeris.kernel.spi.config.KernelProfile;
import eu.exeris.kernel.spi.exceptions.bootstrap.SubsystemCircularDependencyException;
import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

import java.util.Set;

// AvoidDuplicateLiterals: "Exeris", "Bootstrap", "Duration (ms)" are JFR annotation
// literals — they cannot be extracted to constants as @Label/@Category require string literals.
// UseExplicitTypes: 'var' is used for JFR event locals; explicit type would duplicate
// the inner class name on the same line, harming readability with zero type-safety gain.
@SuppressWarnings({
    "PMD.AvoidDuplicateLiterals",
    "PMD.UseExplicitTypes"
})
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
    @Category({"Exeris", "Bootstrap"})
    @Description("Emitted when a kernel subsystem completes initialize()")
    @StackTrace(false)
    public static final class SubsystemInitializedEvent extends Event {

        @Label("Subsystem Name")
        @Description("Unique subsystem identifier (e.g., 'memory', 'persistence')")
        public String subsystemName;

        @Label("Duration (ms)")
        @Description("Wall-clock time for initialize() in milliseconds")
        public long durationMs;

        @Label("Profile")
        @Description("Active kernel profile (dev/test/prod)")
        public KernelProfile profile;

        @Label("Phase")
        @Description("Bootstrap phase: FOUNDATION, SERVICES, or RUNTIME")
        public String phase;

        @Label("Success")
        @Description("Whether initialize() completed without exception")
        public boolean success;

        @Label("Error Message")
        @Description("Exception message if success=false, empty otherwise")
        public String errorMessage = "";
    }

    // =========================================================================
    // Event: SubsystemStarted
    // =========================================================================

    /**
     * Emitted when a subsystem completes {@code start()}.
     */
    @Name("eu.exeris.kernel.bootstrap.SubsystemStarted")
    @Label("Subsystem Started")
    @Category({"Exeris", "Bootstrap"})
    @StackTrace(false)
    public static final class SubsystemStartedEvent extends Event {

        @Label("Subsystem Name")
        public String subsystemName;

        @Label("Duration (ms)")
        public long durationMs;

        @Label("Phase")
        @Description("Bootstrap phase: FOUNDATION, SERVICES, or RUNTIME")
        public String phase;
    }

    // =========================================================================
    // Event: SubsystemStopped
    // =========================================================================

    /**
     * Emitted when a subsystem completes {@code stop()} during graceful shutdown.
     */
    @Name("eu.exeris.kernel.bootstrap.SubsystemStopped")
    @Label("Subsystem Stopped")
    @Category({"Exeris", "Bootstrap"})
    @StackTrace(false)
    public static final class SubsystemStoppedEvent extends Event {

        @Label("Subsystem Name")
        public String subsystemName;

        @Label("Duration (ms)")
        public long durationMs;
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
    @Category({"Exeris", "Bootstrap"})
    @StackTrace(false)
    public static final class KernelBootReadyEvent extends Event {

        @Label("Total Duration (ms)")
        public long totalDurationMs;

        @Label("Subsystem Count")
        public int subsystemCount;

        @Label("Profile")
        public KernelProfile profile;

        @Label("Node ID")
        public String nodeId;

        @Label("Selector")
        public String selector;
    }

    // =========================================================================
    // Event: KernelShutdownComplete
    // =========================================================================

    /**
     * Emitted when all subsystems have been stopped and the kernel has shut down cleanly.
     */
    @Name("eu.exeris.kernel.bootstrap.KernelShutdownComplete")
    @Label("Kernel Shutdown Complete")
    @Category({"Exeris", "Bootstrap"})
    @StackTrace(false)
    public static final class KernelShutdownCompleteEvent extends Event {

        @Label("Total Duration (ms)")
        public long totalDurationMs;

        @Label("Subsystem Count")
        @Description("Number of subsystems that were stopped")
        public int subsystemCount;
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
    @Category({"Exeris", "Bootstrap", "Config"})
    @StackTrace(false)
    public static final class ConfigSettingsResolvedEvent extends Event {

        @Label("Provider Name")
        public String providerName;

        @Label("Profile")
        public String profile;

        @Label("Duration (ms)")
        public long durationMs;

        @Label("Source")
        @Description("Where config was loaded from: 'env', 'file', 'classpath'")
        public String source;
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
    @Category({"Exeris", "Bootstrap", "Fatal"})
    @StackTrace(true) // stack trace IS useful here — this is a fatal defect
    public static final class CircularDependencyDetectedEvent extends Event {

        @Label("Cycle Members")
        @Description("Comma-separated list of subsystem names forming the cycle")
        public String cycleMembers;

        @Label("Error Code")
        public String errorCode = SubsystemCircularDependencyException.ERROR_CODE;
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
        var event = new SubsystemStoppedEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.subsystemName = name;
        event.durationMs    = (System.nanoTime() - startNanos) / 1_000_000;
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
        var event = new CircularDependencyDetectedEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.cycleMembers = String.join(", ", cycleMembers);
        event.commit();
    }
}


