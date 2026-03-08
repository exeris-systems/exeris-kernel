/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.bootstrap;

import eu.exeris.kernel.spi.exceptions.SubsystemException;
import eu.exeris.kernel.spi.exceptions.bootstrap.SubsystemCircularDependencyException;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Lifecycle contract for a single kernel subsystem.
 *
 * <h2>The Wall</h2>
 * <p>This interface is the only coupling point between the bootstrap orchestrator
 * and any subsystem. The orchestrator knows <em>nothing</em> about specific
 * persistence, transport, or cryptography backends — only {@code Subsystem}.
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
 * <p>{@link #dependsOn()} feeds the Kahn's topological sort in
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
     * Names of subsystems whose {@link #initialize()} must complete before this
     * subsystem's own {@link #initialize()} is invoked, and whose {@link #start()}
     * must complete before this subsystem's own {@link #start()} is invoked.
     *
     * <p>In other words, the same dependency graph governs both phases:
     * <ul>
     *   <li>During {@link #initialize()}: dependencies must be in the
     *       {@code INITIALIZED} state — they are not required to be {@code RUNNING} yet.</li>
     *   <li>During {@link #start()}: dependencies must be in the {@code RUNNING}
     *       state before this subsystem starts.</li>
     * </ul>
     *
     * <p>The list feeds {@code SubsystemOrchestrator}'s Kahn's topological sort.
     * If a listed name is not present in the registry (because its provider is not
     * on the classpath), the orchestrator throws {@link SubsystemException} with
     * phase {@link SubsystemException.Phase#INITIALIZE}.
     *
     * <p>Returning an empty list means "no dependencies" — may be initialized and
     * started without waiting for other subsystems.
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
     * <p>Throws {@link eu.exeris.kernel.spi.exceptions.SubsystemException}
     * (unchecked) if initialization fails unrecoverably.
     */
    void initialize();

    /**
     * Phase 2 of the lifecycle — activates the subsystem and begins accepting work.
     *
     * <p>Called by the orchestrator only after {@code initialize()} has returned
     * cleanly and all {@link #dependsOn()} subsystems have completed {@code start()}.
     *
     * <p>Throws {@link eu.exeris.kernel.spi.exceptions.SubsystemException}
     * (unchecked) if startup fails unrecoverably.
     */
    void start();

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

    /**
     * Returns a function that enriches the kernel's {@link ScopedValue} carrier with
     * this subsystem's provider bindings after {@link #initialize()} completes successfully.
     *
     * <h2>Purpose — solving the ScopedValue bootstrap problem</h2>
     * <p>{@link #initialize()} executes inside an already open {@link ScopedValue} scope
     * (the one opened for {@code CURRENT_CONFIG}). It cannot extend that scope from within.
     * This method is the type-safe exit hatch: the {@code SubsystemOrchestrator} calls it
     * <em>after</em> {@code initialize()} returns cleanly, composes all subsystem operators
     * in topological order into a single {@link ScopedValue.Carrier}, then re-enters
     * {@code start()} inside that enriched scope.
     *
     * <h2>Why {@code UnaryOperator} instead of {@code Map<ScopedValue<?>, Object>}</h2>
     * <p>The functional approach gives the compiler full type visibility at each
     * {@link ScopedValue.Carrier#where(ScopedValue, Object)} call site.
     * When a subsystem writes:
     * <pre>{@code
     *   carrier.where(KernelProviders.MEMORY_ALLOCATOR, this.memoryAllocator)
     * }</pre>
     * the compiler knows {@code MEMORY_ALLOCATOR} is {@code ScopedValue<MemoryAllocator>}
     * and rejects any value of the wrong type at compile time.
     * The previous {@code Map<ScopedValue<?>, Object>} accepted any {@code Object} for any
     * key — a type hole that required {@code @SuppressWarnings("unchecked")} in the
     * orchestrator. That suppress is now entirely gone.
     *
     * <h2>Contract</h2>
     * <ul>
     *   <li>Called <strong>after</strong> {@link #initialize()} returns without throwing.</li>
     *   <li>Never called if {@code initialize()} threw.</li>
     *   <li>Must return a non-{@code null} operator; use the identity for no bindings.</li>
     *   <li>The operator MUST be pure — no side effects, no I/O, no state mutation.</li>
     * </ul>
     *
     * <h2>Default behaviour</h2>
     * <p>Returns the identity function — subsystems that expose no SPI provider instances
     * need not override this method.
     *
     * <h2>Example — memory subsystem</h2>
     * <pre>{@code
     * @Override
     * public UnaryOperator<ScopedValue.Carrier> providerBindings() {
     *     return carrier -> carrier
     *         .where(KernelProviders.MEMORY_PROVIDER,  this.memoryProvider)
     *         .where(KernelProviders.MEMORY_ALLOCATOR, this.memoryAllocator);
     * }
     * }</pre>
     *
     * @return a function that appends this subsystem's bindings to a carrier; never {@code null}
     * @since 0.5.0
     * @see eu.exeris.kernel.spi.context.KernelProviders
     */
    default UnaryOperator<ScopedValue.Carrier> providerBindings() {
        return carrier -> carrier;
    }
}
