/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.flow;

import eu.exeris.kernel.spi.flow.model.FlowDefinition;
import eu.exeris.kernel.spi.flow.model.FlowDefinitionMigration;
import eu.exeris.kernel.spi.flow.model.FlowExecutionPlan;

/**
 * SPI: Compiles {@link FlowDefinition} instances into executable {@link FlowExecutionPlan}s
 * and provides a fluent builder for constructing definitions.
 *
 * <h2>Two Responsibilities</h2>
 * <ol>
 *   <li><b>Build</b> — {@link #newDefinition(String)} returns a {@link FlowDefinitionBuilder}
 *       for constructing a {@link FlowDefinition} step by step.</li>
 *   <li><b>Compile</b> — {@link #compile(FlowDefinition)} converts a completed definition
 *       into a ready-to-schedule {@link FlowExecutionPlan}.</li>
 * </ol>
 *
 * <p>Build and compile are two phases of one lifecycle, which is why they share an interface
 * rather than being split into two that would each need the same {@code compile()} signature.
 * Obtain this factory via {@link FlowEngine#plans()}.
 *
 * <p><b>Allocation:</b> allocates (a builder per {@link #newDefinition}, a plan per
 * {@link #compile}) — both are definition-registration calls, not step-execution ones, so the
 * cost is paid before the flows that use the plan run.
 * <p><b>Thread confinement:</b> any thread — both methods are safe to call from any virtual
 * thread once {@link FlowEngine#start()} has returned.
 * <p><b>Ownership:</b> the engine owns the compiled plan and the catalog slot it occupies, and
 * retains it for the engine's lifetime; {@link FlowEngineConfig#maxExecutionPlans()} bounds that
 * catalog, counting each retained definition version separately, and retiring a version is an
 * operator action the kernel does not reclaim on its own.
 *
 * @implSpec Neither method may hold a long-lived lock that would stall a carrier thread.
 * @implNote The Community binding returns a heap-based builder and a heap-backed plan. The
 *           Enterprise binding returns a builder that validates step count against slab capacity at
 *           build time, and compiles by writing descriptors straight into pre-allocated slab pools,
 *           so nothing lands on the heap after {@link FlowEngine#start()}.
 * @since 0.5
 * @see FlowDefinitionBuilder
 * @see FlowDefinition
 * @see FlowExecutionPlan
 * @see FlowEngine#plans()
 */
public interface FlowExecutionPlanFactory {

    /**
     * Returns a fluent builder for constructing a new {@link FlowDefinition}.
     *
     * <p>The builder accumulates step and transition descriptors and produces
     * an immutable {@link FlowDefinition} via {@link FlowDefinitionBuilder#build()}.
     * Safe to call from any virtual thread after {@link FlowEngine#start()}.
     *
     * @param definitionName the unique name for the new flow definition; must not be blank
     * @return a new, empty {@link FlowDefinitionBuilder} bound to {@code definitionName}; never
     *         {@code null}. It is confined to the calling thread and produces one definition
     * @apiNote Assemble every version of a definition through this builder, declaring the version
     *          with {@link FlowDefinitionBuilder#version(int)} — the factory records a definition's
     *          transitions when {@link FlowDefinitionBuilder#build()} runs, so a definition record
     *          built by hand under a name this builder never saw compiles without its edges.
     */
    FlowDefinitionBuilder newDefinition(String definitionName);

    /**
     * Compiles the given {@link FlowDefinition} into a ready-to-execute {@link FlowExecutionPlan}.
     *
     * <p>Safe to call from any virtual thread after {@link FlowEngine#start()}. The plan is
     * catalogued under {@code (name, version)}, so compiling a new version leaves the versions
     * already there in place for the sagas parked under them.
     *
     * @param definition the flow definition to compile; must not be {@code null}
     * @return the compiled execution plan, ready to hand to
     *         {@link FlowScheduler#schedule(FlowExecutionPlan, eu.exeris.kernel.spi.flow.model.FlowContext)};
     *         never {@code null}
     * @throws eu.exeris.kernel.spi.exceptions.flow.FlowEngineException {@code EX-FLOW-7002} with
     *         {@code phase="COMPILE"} and {@code reasonCode="COMPILE_FAILED"} if compilation fails
     *         (e.g., slab pool exhausted in Enterprise, or invalid step graph topology)
     */
    FlowExecutionPlan compile(FlowDefinition definition);

    /**
     * Registers a transform that moves a parked saga from {@code fromVersion} to
     * {@code fromVersion + 1} (ADR-064).
     *
     * <p>Adjacent hops only, enforced here rather than discovered later: the runtime chains
     * registered hops to reach a version it hosts, and requiring adjacency at registration is what
     * makes that chain terminate by construction. It also keeps every lookup a point-get — the plan
     * catalogue offers no name-to-versions index, and adding a scan to the resume success path would
     * cost more than the feature is worth.
     *
     * <p>Registering is optional and additive. An application that registers none keeps the
     * unmigrated behaviour: a saga parked under a version this engine no longer hosts is refused
     * rather than moved, and the refusal leaves the row recoverable.
     *
     * @param definitionName the definition these versions belong to; must not be {@code null} or blank
     * @param fromVersion    the version being migrated away from; must be {@code >= 1}
     * @param migration      the transform; must not be {@code null}
     * @throws eu.exeris.kernel.spi.exceptions.flow.FlowEngineException {@code EX-FLOW-7002} if a
     *         migration is already registered for that definition and version — a second transform
     *         is refused rather than silently replacing the first
     * @throws UnsupportedOperationException if this factory does not support in-flight migration
     * @implSpec The default refuses. {@code eu.exeris.kernel.spi.flow} is a stable package, and an
     *           abstract method added to a stable interface compiles away every existing
     *           implementor; the default keeps them compiling without fabricating migration
     *           support. A factory that cannot chain versions has to say so rather than accept a
     *           transform it will never apply — a registration silently swallowed reports success
     *           for work that will not happen, and the saga it was meant to carry is then refused
     *           later with a reason pointing at the wrong remedy. An implementation supporting
     *           ADR-064 overrides this.
     * @implNote Binary compatibility would not have required the default: a method added to an
     *           interface links fine until it is invoked. Source compatibility does.
     *           {@code FlowSnapshotStore.listParked()} carries a default under the same rule and
     *           takes the <em>opposite</em> disposition — it returns an empty list — because an
     *           empty parked list is a truthful degenerate answer for a store that tracks none,
     *           whereas there is no truthful degenerate answer to "register this transform". Read
     *           the two together as "a stable interface needs a default", never as "defaults
     *           no-op".
     * @since 0.11
     */
    default void registerMigration(String definitionName, int fromVersion, FlowDefinitionMigration migration) {
        throw new UnsupportedOperationException(
                "This FlowExecutionPlanFactory does not support in-flight definition migration (ADR-064)");
    }
}

