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
import eu.exeris.kernel.spi.flow.model.FlowStepAction;
import eu.exeris.kernel.spi.flow.model.FlowTransitionDescriptor;

/**
 * SPI: Fluent builder for constructing a {@link FlowDefinition}.
 *
 * <h2>Usage</h2>
 * <pre>{@code
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
 * }</pre>
 *
 * <h2>Design</h2>
 * <p>This interface replaces the legacy {@code SagaBuilder}. It separates the
 * <em>definition construction</em> concern (fluent step/transition accumulation) from
 * the <em>compilation</em> concern ({@link FlowExecutionPlanFactory#compile}).
 * Both concerns are accessed through {@link FlowExecutionPlanFactory} via
 * {@link FlowEngine#plans()} — a single entry point, no duplicate interfaces.
 *
 * <h2>Thread Safety</h2>
 * <p>Builder instances are <strong>not</strong> thread-safe. Create one builder per
 * definition and do not share it across threads.
 *
 * @since 0.5.0
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
     * Sets the definition version (ADR-064). Defaults to
     * {@link eu.exeris.kernel.spi.flow.model.FlowDefinition#INITIAL_VERSION} when not set.
     *
     * <h2>Why this exists</h2>
     * <p>ADR-064 made {@code (name, version)} the plan's identity: the catalog is keyed by the pair,
     * a parked saga resumes on the exact version it parked under, and an unhosted version fails
     * closed. {@link FlowDefinition} carries the version accordingly — but until 0.12 this builder,
     * the only supported way to assemble a definition, had no way to set it. Every definition built
     * through the fluent API was version 1, and a second version was unexpressible.
     *
     * <p>The workaround was to build through the builder and then rebuild the record by hand with
     * the five-argument {@link FlowDefinition} constructor, which the kernel's own versioning TCK
     * did. That works only by side effect: the Core factory records a definition's transitions when
     * {@link #build()} runs, keyed by name, so the hand-built record inherits them. An application
     * that constructed a versioned {@code FlowDefinition} <em>without</em> first building one under
     * the same name compiled to a plan with steps and <b>no transitions</b> — a flow graph with no
     * edges, and no diagnostic.
     *
     * <h2>Default behaviour</h2>
     * <p>{@code default}, and it throws. An interface this old cannot grow an abstract method
     * without breaking every out-of-tree implementation at invoke time — the same constraint that
     * gave {@link eu.exeris.kernel.spi.flow.model.FlowExecutionPlan#definitionVersion()} its
     * default. But the choice of default differs on purpose: returning a value there is safe,
     * whereas silently ignoring a requested version here would build a v1 definition that claims to
     * be v3, which is precisely the confusion ADR-064 exists to prevent. A builder that cannot
     * version says so.
     *
     * @param version definition version; must be &gt;= {@code FlowDefinition.INITIAL_VERSION}
     * @return {@code this} builder for chaining
     * @throws UnsupportedOperationException if this builder does not support versioning
     * @since 0.12.0
     */
    default FlowDefinitionBuilder version(int version) {
        throw new UnsupportedOperationException(
                "this FlowDefinitionBuilder does not support definition versions (ADR-064)");
    }

    /**
     * Builds and returns the immutable {@link FlowDefinition}.
     *
     * @return a validated, immutable flow definition; never {@code null}
     * @throws eu.exeris.kernel.spi.exceptions.flow.FlowEngineException if the definition
     *         is invalid (e.g., no steps, step count exceeds slab capacity in Enterprise)
     */
    FlowDefinition build();
}

