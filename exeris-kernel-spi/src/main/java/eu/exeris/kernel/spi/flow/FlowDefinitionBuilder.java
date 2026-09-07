/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.flow;

import eu.exeris.kernel.spi.flow.model.FlowDefinition;
import eu.exeris.kernel.spi.flow.model.FlowStepAction;
import eu.exeris.kernel.spi.flow.model.FlowTransitionDescriptor;

/**
 * SPI: Fluent builder for constructing a {@link FlowDefinition}.
 *
 * <h2>Usage</h2>
 * {@snippet lang="java" :
 * FlowDefinition def = engine.plans()
 *     .newDefinition("order-fulfillment")
 *     .step("reserve-stock",    this::reserveStock,    this::releaseStock)
 *     .step("charge-payment",   this::chargePayment,   this::refundPayment)
 *     .step("dispatch-order",   this::dispatchOrder,   null)
 *     .transition(0, 1)
 *     .transition(1, 2)
 *     .timeoutDuration(300_000_000_000L)
 *     .maxRetries(3)
 *     .build();
 * }
 *
 * <h2>Design</h2>
 * <p>The builder separates the <em>definition construction</em> concern (fluent step and
 * transition accumulation) from the <em>compilation</em> concern
 * ({@link FlowExecutionPlanFactory#compile}). Both are reached through
 * {@link FlowExecutionPlanFactory} via {@link FlowEngine#plans()} — a single entry point.
 *
 * <p><b>Thread confinement:</b> owner thread — a builder is <strong>not</strong> thread-safe;
 * create one per definition and do not share it across threads.
 *
 * @since 0.5
 * @see FlowExecutionPlanFactory
 * @see FlowDefinition
 */
public interface FlowDefinitionBuilder {

    /**
     * Appends a step with an optional compensation action.
     *
     * <p>Steps are numbered sequentially in the order they are added (0-based).
     * The step name must be unique within this definition.
     *
     * @param name         unique human-readable name for the step; must not be blank
     * @param action       the forward action; must not be {@code null}
     * @param compensation the backward compensation action; {@code null} if not required
     * @return {@code this} builder for chaining
     */
    FlowDefinitionBuilder step(String name, FlowStepAction action, FlowStepAction compensation);

    /**
     * Registers a directed transition between two step indices.
     *
     * <p>Step indices refer to the 0-based insertion order from {@link #step}.
     * Use {@link FlowTransitionDescriptor#unconditional(int, int)} semantics —
     * the condition tag defaults to {@code "default"}.
     *
     * @param fromStep source step index (0-based)
     * @param toStep   target step index (0-based)
     * @return {@code this} builder for chaining
     */
    FlowDefinitionBuilder transition(int fromStep, int toStep);

    /**
     * Registers a conditional transition with an explicit condition tag.
     *
     * @param fromStep     source step index (0-based)
     * @param toStep       target step index (0-based)
     * @param conditionTag routing condition tag; must not be blank
     * @return {@code this} builder for chaining
     */
    FlowDefinitionBuilder transition(int fromStep, int toStep, String conditionTag);

    /**
     * Sets the default flow duration limit in nanoseconds.
     *
     * <p>If not set, the engine's {@link FlowEngineConfig#timeoutDurationNanos()} default
     * is used when compiling this definition into a plan.
     *
     * @param durationNanos duration in nanoseconds; must be &gt; 0
     * @return {@code this} builder for chaining
     */
    FlowDefinitionBuilder timeoutDuration(long durationNanos);

    /**
     * Sets the maximum number of step-level retries before backward compensation is triggered.
     *
     * @param maxRetries retry count; must be &gt;= 0
     * @return {@code this} builder for chaining
     */
    FlowDefinitionBuilder maxRetries(int maxRetries);

    /**
     * Declares the version this definition carries, making {@code (name, version)} its identity in
     * the plan catalog (ADR-064): registering a new version does not evict the old one, a parked
     * saga resumes on the exact version it parked under, and a version this engine does not host
     * fails closed rather than rebinding. A definition built without this call is
     * {@link eu.exeris.kernel.spi.flow.model.FlowDefinition#INITIAL_VERSION}.
     *
     * @param version definition version; must be &gt;= {@code FlowDefinition.INITIAL_VERSION}
     * @return {@code this} builder for chaining
     * @throws UnsupportedOperationException if this builder does not support versioning
     * @implSpec A builder that cannot record a version must throw rather than ignore the argument:
     *           building a version-1 definition that claims to be version 3 is exactly the confusion
     *           ADR-064 exists to prevent, and a builder that cannot version says so. The method is
     *           a {@code default} because an interface this stable cannot grow an abstract method
     *           without breaking every out-of-tree implementation at invoke time — the same
     *           constraint that gave
     *           {@link eu.exeris.kernel.spi.flow.model.FlowExecutionPlan#definitionVersion()} its
     *           default, where returning a value is the safe disposition and here it is not.
     * @apiNote Declare the version here rather than rebuilding the record by hand through the
     *          five-argument {@link FlowDefinition} constructor. A hand-built versioned definition
     *          whose name was never assembled through this builder compiles to a plan that carries
     *          its steps and <b>no declared edges</b>, with no diagnostic: a step with no outgoing
     *          transition falls back to {@code index + 1}, so a sequential definition is unaffected
     *          and one declaring a skip or a branch silently runs a path it never declared.
     * @implNote Edges survive such a hand-rebuild only by side effect — the Core factory records a
     *           definition's transitions when {@link #build()} runs and hands them to
     *           {@code compile}, so a record rebuilt under a name that was built first inherits them.
     * @since 0.12
     */
    default FlowDefinitionBuilder version(int version) {
        throw new UnsupportedOperationException(
                "this FlowDefinitionBuilder does not support definition versions (ADR-064)");
    }

    /**
     * Builds and returns the immutable {@link FlowDefinition}.
     *
     * @return a validated, immutable flow definition; never {@code null}
     * @throws eu.exeris.kernel.spi.exceptions.flow.FlowEngineException {@code EX-FLOW-7002} if the
     *         definition is invalid (e.g., no steps, step count exceeds slab capacity in Enterprise)
     */
    FlowDefinition build();
}

