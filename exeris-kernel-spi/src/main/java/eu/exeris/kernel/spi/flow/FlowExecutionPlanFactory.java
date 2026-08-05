/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
     * @param definitionName the definition these versions belong to; must not be {@code null} or blank
     * @param fromVersion    the version being migrated away from; must be {@code >= 1}
     * @param migration      the transform; must not be {@code null}
     * @throws eu.exeris.kernel.spi.exceptions.flow.FlowEngineException if a migration is already
     *         registered for that definition and version
     * @since 0.11.0
     */
    void registerMigration(String definitionName, int fromVersion, FlowDefinitionMigration migration);
}

