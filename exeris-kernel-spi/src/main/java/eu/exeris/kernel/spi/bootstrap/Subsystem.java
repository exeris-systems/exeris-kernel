/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 * <p><b>Allocation:</b> allocates (in {@link #initialize()}, where the subsystem acquires
 * its own resources) — every method declared here is invoked once per kernel lifecycle,
 * so none of them sits on a request hot path.
 * <p><b>Thread confinement:</b> owner thread — the orchestrator drives one instance's
 * callbacks in sequence and never concurrently: {@code start()} only after
 * {@link #initialize()} has returned cleanly, {@code stop()} only while
 * {@link #isRunning()} reports {@code true}. <em>Which</em> thread that is belongs to the
 * orchestrator rather than to this contract; see {@link BootstrapPhase} for the reason the
 * Core orchestrator uses the thread that called {@code boot()}.
 * <p><b>Ownership:</b> the subsystem owns everything it acquires in {@link #initialize()}
 * and releases it in {@link #stop()}, which the orchestrator calls in reverse topological
 * order so that dependents are torn down before their dependencies.
 *
 * @since 0.5
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
     * @return the identifier other subsystems name in {@link #dependsOn()}; never
     *         {@code null} and never blank
     * @implSpec Implementations must return the same value on every call — the orchestrator
     *           indexes the registry by this name before {@link #initialize()} runs and again
     *           at shutdown — and must keep it lowercase and hyphen-separated, because
     *           {@link BootstrapSelector#forNames(String...)} normalises requested names to
     *           that form and matches them literally.
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
     * A name that no active provider supplies — its module is not on the classpath — aborts
     * the boot before any subsystem is initialized; the dependency-graph build reports it as
     * {@code EX-BOOT-0002}. A cycle aborts it as
     * {@link SubsystemCircularDependencyException} ({@code EX-BOOT-0001}).
     *
     * <p>Returning an empty list means "no dependencies" — may be initialized and
     * started without waiting for other subsystems.
     *
     * @return immutable ordered list of dependency names; never {@code null}
     * @implSpec Implementations must return the same names on every call, and must not hand
     *           out a list they later mutate: the orchestrator reads the graph once to expand
     *           the selector closure and again to sort it, and a list that differs between
     *           those reads yields a boot order that satisfies neither.
     */
    List<String> dependsOn();

    /**
     * Places this subsystem in a boot phase, fixing which other subsystems must already be
     * {@code RUNNING} before this one is initialized.
     *
     * <p>{@link BootstrapPhase#FOUNDATION} additionally makes the subsystem mandatory: it is
     * never skipped under a {@code DEGRADE} failure policy, whatever {@link #isOptional()}
     * returns.
     *
     * @return the phase this subsystem is initialized and started in; never {@code null}
     * @implSpec Implementations must return the same phase on every call; the orchestrator
     *           reads it while grouping the registry and again while starting each round.
     */
    BootstrapPhase phase();

    /**
     * Phase 1 of the lifecycle — allocates resources and validates configuration.
     *
     * <p>Runs after every subsystem named by {@link #dependsOn()} has reached
     * {@code INITIALIZED}. Configuration is readable here: the orchestrator invokes this
     * method inside the kernel scope that binds
     * {@link eu.exeris.kernel.spi.context.KernelProviders#CURRENT_CONFIG}.
     *
     * @throws SubsystemException ({@code EX-BOOT-0002}) if initialization fails
     *         unrecoverably; any other unchecked exception is wrapped in one by the
     *         orchestrator, with {@link SubsystemException.Phase#INITIALIZE} as its phase
     * @implSpec Implementations must be idempotent — the orchestrator calls this exactly once
     *           per kernel lifecycle, but a test harness or a re-entrant host need not —
     *           and must not begin accepting external requests or connections here; that is
     *           {@link #start()}'s job.
     */
    void initialize();

    /**
     * Phase 2 of the lifecycle — activates the subsystem and begins accepting work.
     *
     * <p>Called by the orchestrator only after {@code initialize()} has returned
     * cleanly and all {@link #dependsOn()} subsystems have completed {@code start()}.
     * Every binding contributed by {@link #providerBindings()} — this subsystem's own and
     * those of the subsystems initialized before it — is visible from here.
     *
     * @throws SubsystemException ({@code EX-BOOT-0002}) if startup fails unrecoverably;
     *         any other unchecked exception is wrapped in one by the orchestrator, with
     *         {@link SubsystemException.Phase#START} as its phase
     * @implSpec A subsystem that acquires anything the kernel must later release has to
     *           report {@code true} from {@link #isRunning()} once this method returns;
     *           the orchestrator skips {@link #stop()} for a subsystem that reports
     *           {@code false}.
     */
    void start();

    /**
     * Phase 3 — graceful shutdown. Flushes in-flight work and releases resources.
     *
     * <p>The orchestrator calls {@code stop()} in reverse topological order so that
     * dependents are always stopped before their dependencies, and only for subsystems
     * whose {@link #isRunning()} reports {@code true}.
     *
     * @implSpec Implementations must not throw from this method, and must not leave a
     *           resource unreleased because a preceding release failed: shutdown has no
     *           second attempt and no failure policy — a subsystem that throws here is the
     *           last one the kernel hears from about that resource.
     * @implNote The Core orchestrator swallows any unchecked exception thrown here, logs it
     *           at {@code WARNING}, and continues with the next subsystem; the subsystem is
     *           then not marked {@code STOPPED}.
     */
    void stop();

    /**
     * Reports whether this subsystem is currently between {@code start()} and
     * {@code stop()} — the orchestrator's sole criterion for calling {@link #stop()}.
     *
     * @return {@code true} while the subsystem holds resources that {@link #stop()} must
     *         release; {@code false} before {@code start()} and after {@code stop()}
     * @implSpec The default returns {@code false}, which tells the orchestrator there is
     *           nothing to shut down. Any subsystem that acquires resources must override
     *           this, or {@link #stop()} is never called and those resources leak for the
     *           life of the JVM.
     */
    default boolean isRunning() {
        return false;
    }

    /**
     * Reports whether the kernel may finish booting without this subsystem when it fails
     * and the active failure policy is {@code DEGRADE}.
     *
     * <p>{@link BootstrapPhase#FOUNDATION} subsystems are treated as mandatory by
     * the orchestrator regardless of this value, and a {@code FAIL_FAST} policy ignores the
     * flag entirely — under it, every failure aborts the boot.
     *
     * @return {@code true} if a failure of this subsystem may be skipped instead of aborting
     *         the boot
     * @apiNote Optional is not free: a skipped subsystem takes its transitive dependents out
     *          of the boot with it, so declaring one subsystem optional can silently remove
     *          a whole branch of the graph from a running kernel.
     * @implSpec The default returns {@code false} — a subsystem is mandatory unless it says
     *           otherwise.
     */
    default boolean isOptional() {
        return false;
    }

    /**
     * Returns a function that enriches the kernel's {@link ScopedValue} carrier with
     * this subsystem's provider bindings after {@link #initialize()} completes successfully
     * and before {@link #start()} is invoked.
     *
     * <p>This is how a subsystem publishes the SPI instances it owns — a memory allocator, a
     * persistence engine — to everything that runs later in the boot. {@link #initialize()}
     * itself executes inside an already open {@link ScopedValue} scope (the one opened for
     * {@link eu.exeris.kernel.spi.context.KernelProviders#CURRENT_CONFIG}) and cannot extend
     * that scope from within, so the orchestrator collects these operators instead and applies
     * them once, when it builds the scope that {@link #start()} and the running kernel observe.
     *
     * <p>Bindings are composed only for subsystems whose {@link #initialize()} returned without
     * throwing; a subsystem that failed or was skipped contributes none. Binding visibility
     * therefore matches admission to the lifecycle exactly — no half-initialized subsystem's
     * provider is ever reachable through {@code KernelProviders}.
     *
     * {@snippet lang="java" :
     * public UnaryOperator<ScopedValue.Carrier> providerBindings() {
     *     return carrier -> carrier
     *             .where(KernelProviders.MEMORY_PROVIDER,  this.memoryProvider)
     *             .where(KernelProviders.MEMORY_ALLOCATOR, this.memoryAllocator);
     * }
     * }
     *
     * @return a function that appends this subsystem's bindings to a carrier; never {@code null}
     * @implSpec The returned operator must be pure — no side effects, no I/O, no state
     *           mutation — and must return a non-{@code null} carrier; the orchestrator applies
     *           it more than once. Return the identity when the subsystem publishes nothing.
     *           The default implementation returns the identity function, so a subsystem that
     *           exposes no SPI provider instance need not override this method.
     * @apiNote Binding through a function rather than a {@code Map<ScopedValue<?>, Object>}
     *          keeps every key checked against its own value type at the
     *          {@link ScopedValue.Carrier#where(ScopedValue, Object)} call site: because
     *          {@code KernelProviders.MEMORY_ALLOCATOR} is declared as
     *          {@code ScopedValue<MemoryAllocator>}, a value of any other type is rejected by
     *          the compiler rather than at boot.
     * @implNote The Core orchestrator distinguishes a real operator from the identity by
     *           applying it to a throw-away probe carrier and comparing the result by
     *           reference — a second reason the operator must not carry side effects.
     * @since 0.5
     * @see eu.exeris.kernel.spi.context.KernelProviders
     */
    default UnaryOperator<ScopedValue.Carrier> providerBindings() {
        return carrier -> carrier;
    }
}
