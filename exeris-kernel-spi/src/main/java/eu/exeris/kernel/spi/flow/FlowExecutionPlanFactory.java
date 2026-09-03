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
 *       for constructing a {@link FlowDefinition} step-by-step (fluent API, analogous to
 *       the legacy {@code SagaBuilder}).</li>
 *   <li><b>Compile</b> — {@link #compile(FlowDefinition)} converts a completed definition
 *       into a ready-to-schedule {@link FlowExecutionPlan}.</li>
 * </ol>
 *
 * <h2>Why a Single Interface</h2>
 * <p>Build and compile are two phases of the same lifecycle: a definition is first
 * constructed (build), then compiled into a plan (compile). Keeping them together
 * avoids the DRY violation that would result from having separate {@code FlowBuilder}
 * and {@code FlowExecutionPlanFactory} interfaces with identical {@code compile()}
 * signatures. Obtain this factory via {@link FlowEngine#plans()}.
 *
 * <h2>Tier Contract</h2>
 * <ul>
 *   <li><b>Community</b>: {@link #newDefinition(String)} returns a heap-based builder.
 *       {@link #compile(FlowDefinition)} returns a heap-backed plan (no off-heap memory).</li>
 *   <li><b>Enterprise</b>: {@link #newDefinition(String)} returns a builder that validates
 *       step count against slab capacity at build time.
 *       {@link #compile(FlowDefinition)} writes descriptors directly into pre-allocated
 *       slab pools — zero heap allocation after {@link FlowEngine#start()}.</li>
 * </ul>
 *
 * @since 0.5.0
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
     * @return a new, empty {@link FlowDefinitionBuilder}; never {@code null}
     */
    FlowDefinitionBuilder newDefinition(String definitionName);

    /**
     * Compiles the given {@link FlowDefinition} into a ready-to-execute {@link FlowExecutionPlan}.
     *
     * <p>Safe to call from any virtual thread after {@link FlowEngine#start()}.
     * Implementations MUST NOT hold long-lived locks that would stall carrier threads.
     *
     * @param definition the flow definition to compile; must not be {@code null}
     * @return compiled execution plan; never {@code null}
     * @throws eu.exeris.kernel.spi.exceptions.flow.FlowEngineException if compilation fails
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
     * <p>Registering is optional and additive. An application that registers none keeps the v0.11
     * behaviour: a saga parked under a version this engine no longer hosts is refused rather than
     * moved, and the refusal leaves the row recoverable.
     *
     * <p><b>Default: refuse.</b> {@code eu.exeris.kernel.spi.flow} is declared stable since 0.5.0, and
     * an abstract method added to a stable interface compiles away every existing implementor. The
     * default keeps them compiling; it does not fabricate migration support, because a factory that
     * cannot chain versions must say so rather than accept a transform it will never apply — a
     * registration silently swallowed is worse than one refused, since the saga it was meant to carry
     * would then be refused later with a reason pointing at the wrong remedy. Implementations that
     * support ADR-064 override this.
     *
     * <p>Binary compatibility would not have required the default: adding a method to an interface
     * links fine until it is invoked. Source compatibility does, and an implementor discovering the
     * difference at the next compile is not a meaningfully better outcome.
     *
     * <p>{@code FlowSnapshotStore.listParked()} was added under the same rule in 0.7.0 and took the
     * <em>opposite</em> disposition — its default returns an empty list. The divergence is the point,
     * not an inconsistency: an empty parked list is a truthful degenerate answer for a store that does
     * not track them, whereas there is no truthful degenerate answer to "register this transform".
     * Accepting one and never applying it would report success for work that will not happen. So the
     * rule generalises and the body does not: read the two together as "a stable interface needs a
     * default", never as "defaults no-op".
     *
     * @param definitionName the definition these versions belong to; must not be {@code null} or blank
     * @param fromVersion    the version being migrated away from; must be {@code >= 1}
     * @param migration      the transform; must not be {@code null}
     * @throws eu.exeris.kernel.spi.exceptions.flow.FlowEngineException if a migration is already
     *         registered for that definition and version
     * @throws UnsupportedOperationException if this factory does not support in-flight migration
     * @since 0.11.0
     */
    default void registerMigration(String definitionName, int fromVersion, FlowDefinitionMigration migration) {
        throw new UnsupportedOperationException(
                "This FlowExecutionPlanFactory does not support in-flight definition migration (ADR-064)");
    }
}

