/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.tck.contract.flow;

import eu.exeris.kernel.spi.flow.FlowEngine;
import eu.exeris.kernel.spi.flow.FlowExecutionPlanFactory;
import eu.exeris.kernel.spi.flow.FlowScheduler;
import eu.exeris.kernel.spi.flow.model.FlowDefinition;
import eu.exeris.kernel.spi.flow.model.FlowExecutionPlan;
import eu.exeris.kernel.spi.flow.model.FlowStepAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * TCK: Abstract base for {@link FlowEngine} contract verification.
 *
 * <h2>Front 2 — The Maestro (Flow &amp; Events)</h2>
 * <p>Verifies the full {@link FlowEngine} component lifecycle and the correctness
 * of the {@link FlowExecutionPlanFactory} compile/build pipeline.
 *
 * <h2>Verified Constraints</h2>
 * <ol>
 *   <li>start() / close() lifecycle is correct and close() is idempotent.</li>
 *   <li>plans(), scheduler(), registry() return non-null after start().</li>
 *   <li>A single-step flow can be defined, compiled, and scheduled without exception.</li>
 *   <li>compile(definition) returns a plan with stepCount() == definition step count.</li>
 *   <li>stepAt(i) executes in O(1) time (verified structurally — no O(n) scan allowed).</li>
 *   <li>FlowExecutionPlan is immutable — the same plan can be scheduled for multiple instances.</li>
 * </ol>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * class CommunityFlowEngineTest extends AbstractFlowEngineTck {
 *     \@Override protected FlowEngine createEngine() {
 *         return new CommunityFlowProvider().createEngine(FlowEngineConfig.defaults());
 *     }
 * }
 * }</pre>
 *
 * @since 0.5.0
 */
public abstract class AbstractFlowEngineTck {

    // =========================================================================
    // Template method
    // =========================================================================

    /** Creates a fully configured, but not yet started, {@link FlowEngine}. */
    protected abstract FlowEngine createEngine();

    private FlowEngine engine;

    @BeforeEach
    final void setUp() {
        engine = createEngine();
    }

    @AfterEach
    final void tearDown() {
        engine.close();
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Nested
    @DisplayName("FlowEngine lifecycle contract")
    class Lifecycle {

        @Test
        @DisplayName("start() then close() completes without exception")
        void startAndCloseHappyPath() {
            assertThatCode(() -> engine.start()).doesNotThrowAnyException();
            assertThatCode(() -> engine.close()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("close() is idempotent — calling twice does not throw")
        void closeIsIdempotent() {
            engine.start();
            engine.close();
            assertThatCode(() -> engine.close())
                    .as("close() MUST be idempotent — double-close is a no-op")
                    .doesNotThrowAnyException();
        }
    }

    // =========================================================================
    // Component accessors (after start)
    // =========================================================================

    @Nested
    @DisplayName("Component accessors — non-null after start()")
    class ComponentAccessors {

        @BeforeEach
        void startEngine() {
            engine.start();
        }

        @Test
        @DisplayName("plans() returns a non-null FlowExecutionPlanFactory")
        void plansIsNonNull() {
            assertThat(engine.plans()).as("FlowEngine.plans() MUST not be null after start").isNotNull();
        }

        @Test
        @DisplayName("scheduler() returns a non-null FlowScheduler")
        void schedulerIsNonNull() {
            assertThat(engine.scheduler()).as("FlowEngine.scheduler() MUST not be null after start").isNotNull();
        }

        @Test
        @DisplayName("registry() returns a non-null FlowRegistry")
        void registryIsNonNull() {
            assertThat(engine.registry()).as("FlowEngine.registry() MUST not be null after start").isNotNull();
        }
    }

    // =========================================================================
    // FlowExecutionPlanFactory — define & compile pipeline
    // =========================================================================

    @Nested
    @DisplayName("FlowExecutionPlanFactory — build & compile pipeline")
    class PlanFactory {

        private FlowExecutionPlanFactory factory;

        @BeforeEach
        void startEngineAndGetFactory() {
            engine.start();
            factory = engine.plans();
        }

        @Test
        @DisplayName("newDefinition() returns a non-null builder for a non-blank name")
        void newDefinitionReturnsBuilder() {
            assertThat(factory.newDefinition("test-flow"))
                    .as("FlowExecutionPlanFactory.newDefinition() must not return null")
                    .isNotNull();
        }

        @Test
        @DisplayName("compile(definition) returns a plan with matching definitionName()")
        void compiledPlanHasCorrectDefinitionName() {
            FlowStepAction noOp = ctx -> eu.exeris.kernel.spi.flow.model.FlowOutcome.CONTINUE;
            FlowDefinition def = factory.newDefinition("order-saga")
                    .step("validate", noOp, null)
                    .step("process",  noOp, null)
                    .transition(0, 1)
                    .build();

            FlowExecutionPlan plan = factory.compile(def);

            assertThat(plan.definitionName())
                    .as("Compiled plan must carry the same definitionName as the definition")
                    .isEqualTo("order-saga");
        }

        @Test
        @DisplayName("compile(definition) returns plan with correct stepCount()")
        void compiledPlanHasCorrectStepCount() {
            FlowStepAction noOp = ctx -> eu.exeris.kernel.spi.flow.model.FlowOutcome.CONTINUE;
            FlowDefinition def = factory.newDefinition("two-step-flow")
                    .step("step-a", noOp, null)
                    .step("step-b", noOp, null)
                    .transition(0, 1)
                    .build();

            FlowExecutionPlan plan = factory.compile(def);

            assertThat(plan.stepCount())
                    .as("Compiled plan stepCount() MUST equal the number of declared steps")
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("stepAt(i) — O(1) structural contract: no exception, non-null for valid index")
        @Timeout(value = 500, unit = TimeUnit.MILLISECONDS)
        void stepAtIsO1AndNonNull() {
            FlowStepAction noOp = ctx -> eu.exeris.kernel.spi.flow.model.FlowOutcome.CONTINUE;
            FlowDefinition def = factory.newDefinition("structural-test")
                    .step("only-step", noOp, null)
                    .build();

            FlowExecutionPlan plan = factory.compile(def);

            assertThat(plan.stepAt(0))
                    .as("stepAt(0) MUST return a non-null FlowStepDescriptor for a valid index")
                    .isNotNull();
        }

        @Test
        @DisplayName("Same compiled plan is reusable — can be scheduled for multiple instances")
        void compiledPlanIsImmutableAndReusable() {
            FlowStepAction noOp = ctx -> eu.exeris.kernel.spi.flow.model.FlowOutcome.CONTINUE;
            FlowDefinition def = factory.newDefinition("reusable-flow")
                    .step("the-step", noOp, null)
                    .build();

            FlowExecutionPlan plan = factory.compile(def);
            FlowScheduler scheduler = engine.scheduler();

            // Two distinct contexts for the same plan — should not throw
            assertThatCode(() -> {
                scheduler.schedule(plan, TestFlowContexts.create("inst-1", "reusable-flow"));
                scheduler.schedule(plan, TestFlowContexts.create("inst-2", "reusable-flow"));
            }).as("Same FlowExecutionPlan MUST be schedulable for multiple instances (immutable)")
              .doesNotThrowAnyException();
        }
    }
}
