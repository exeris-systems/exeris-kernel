/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.bootstrap;

import java.util.List;

/**
 * Lifecycle contract for a single kernel subsystem.
 *
 * <h2>The Wall</h2>
 * <p>This interface is the only coupling point between the bootstrap orchestrator
 * and any subsystem. The orchestrator knows <em>nothing</em> about JDBC, HikariCP,
 * io_uring, Netty, or OpenSSL — only {@code Subsystem}.
 *
 * <h2>Lifecycle State Machine</h2>
 * <pre>
 *   [unregistered] → initialize() → [INITIALIZED] → start() → [RUNNING]
 *                                                                    ↓
 *                                                               stop() → [STOPPED]
 * </pre>
 * <p>Transitions are always forward. The orchestrator will never call
 * {@code start()} before {@code initialize()} returns cleanly.
 *
 * <h2>Dependency Resolution</h2>
 * <p>{@link #dependsOn()} feeds the Kahn's BFS topological sort in
 * {@code SubsystemOrchestrator}. Cycles cause an immediate
 * {@link SubsystemCircularDependencyException} — no recovery attempted.
 *
 * <h2>Phase Assignment</h2>
 * <p>{@link #phase()} groups subsystems into parallel-init buckets.
 * The orchestrator runs each {@link BootstrapPhase} in its entirety before
 * proceeding to the next.
 *
 * <h2>Optionality</h2>
 * <p>If {@link #isOptional()} returns {@code true} and the failure policy is
 * {@code DEGRADE}, a failing subsystem is skipped rather than aborting the kernel.
 * {@link BootstrapPhase#FOUNDATION} subsystems are always considered mandatory
 * regardless of this flag.
 *
 * @since 0.5.0
 */
public interface Subsystem {

    /**
     * Unique, lowercase, hyphen-separated identifier for this subsystem.
     *
     * <p>This name is used as the dependency key in {@link #dependsOn()} declarations,
     * as the subsystem lookup key in {@code BootstrapSelector}, and as the label in
     * JFR telemetry events.
     *
     * <p>Examples: {@code "memory"}, {@code "persistence"}, {@code "transport"}.
     *
     * @return non-null, non-blank subsystem name
     */
    String name();

    /**
     * Names of subsystems that must reach {@code READY} before this one may initialize.
     *
     * <p>The list feeds {@code SubsystemOrchestrator}'s Kahn's BFS topological sort.
     * If a listed name is not present in the registry (because its provider is not
     * on the classpath), the orchestrator throws {@link SubsystemException} with
     * phase {@link SubsystemException.Phase#INITIALIZE}.
     *
     * <p>Returning an empty list means "no dependencies" — may initialize first.
     *
     * @return immutable ordered list of dependency names; never {@code null}
     */
    List<String> dependsOn();

    /**
     * Returns the {@link BootstrapPhase} this subsystem belongs to.
     *
     * <p>Determines whether this subsystem initializes sequentially (FOUNDATION)
     * or in parallel with peers (SERVICES, RUNTIME).
     *
     * @return non-null phase
     */
    BootstrapPhase phase();

    /**
     * Phase 1 of the lifecycle — allocates resources and validates configuration.
     *
     * <p>Must be idempotent if called more than once (though the orchestrator
     * guarantees it is called exactly once per kernel lifecycle).
     * Must NOT start accepting external requests or connections.
     *
     * @throws SubsystemException if initialization fails unrecoverably
     */
    void initialize() throws SubsystemException;

    /**
     * Phase 2 of the lifecycle — activates the subsystem and begins accepting work.
     *
     * <p>Called by the orchestrator only after {@code initialize()} has returned
     * cleanly and all {@link #dependsOn()} subsystems have completed {@code start()}.
     *
     * @throws SubsystemException if startup fails unrecoverably
     */
    void start() throws SubsystemException;

    /**
     * Phase 3 — graceful shutdown. Flushes in-flight work and releases resources.
     *
     * <p>The orchestrator calls {@code stop()} in reverse topological order so that
     * dependents are always stopped before their dependencies.
     * Implementations MUST NOT throw from this method — exceptions are caught and
     * logged as WARN by the orchestrator.
     */
    void stop();

    /**
     * Returns {@code true} if this subsystem is currently between {@code start()} and
     * {@code stop()}. Used by the orchestrator to decide whether to call {@code stop()}.
     *
     * @return {@code true} if running
     */
    default boolean isRunning() {
        return false;
    }

    /**
     * Returns {@code true} if this subsystem may be skipped when it fails and the
     * active failure policy is {@code DEGRADE}.
     *
     * <p>{@link BootstrapPhase#FOUNDATION} subsystems are treated as mandatory by
     * the orchestrator regardless of this value.
     *
     * @return {@code true} if optional
     */
    default boolean isOptional() {
        return false;
    }
}

