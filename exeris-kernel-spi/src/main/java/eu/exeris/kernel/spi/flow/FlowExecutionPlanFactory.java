/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.flow;

import eu.exeris.kernel.spi.flow.model.FlowDefinition;
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
}

