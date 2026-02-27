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
 * SPI: Compiles a {@link FlowDefinition} into an executable {@link FlowExecutionPlan}.
 *
 * <h2>Tier Contract</h2>
 * <ul>
 *   <li><b>Community</b>: returns a heap-based plan backed by {@code ArrayList}.
 *       Steps are copied from the definition, no off-heap memory consumed.</li>
 *   <li><b>Enterprise</b>: writes step and transition descriptors directly into
 *       pre-allocated slab pools. The returned plan is a thin wrapper that holds
 *       the raw base addresses of the off-heap descriptor arrays.
 *       Zero heap allocation after {@link FlowEngine#start()}.</li>
 * </ul>
 *
 * @since 0.5.0
 * @see FlowDefinition
 * @see FlowExecutionPlan
 */
// Intentionally not @FunctionalInterface — future default methods planned.
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface FlowBuilder {
    /**
     * Compiles the given {@link FlowDefinition} into a ready-to-execute {@link FlowExecutionPlan}.
     *
     * <p>This method is safe to call from any virtual thread after {@link FlowEngine#start()}.
     * Implementations MUST NOT hold a long-lived lock that would stall carrier threads.
     *
     * @param definition the flow definition to compile; must not be {@code null}
     * @return compiled execution plan; never {@code null}
     * @throws eu.exeris.kernel.spi.exceptions.flow.FlowEngineException if compilation fails
     *         (e.g., slab pool exhausted in Enterprise, or invalid step graph)
     */
    FlowExecutionPlan compile(FlowDefinition definition);
}
