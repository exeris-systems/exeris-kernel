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
import eu.exeris.kernel.spi.flow.model.FlowExecutionPlan;
import eu.exeris.kernel.spi.flow.model.FlowMigrationState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code registerMigration} is additive on a surface declared stable since 0.5.0.
 *
 * <p>Two obligations that pull against each other, both asserted here for the same reason the
 * {@code FlowSnapshot} compatibility bridge asserts both of its halves: an implementor written before
 * v0.11 must still compile, and the default must not fabricate support it does not have.
 *
 * <p>The compile half is asserted by this file existing. {@link PreV011Factory} implements only the
 * two methods the interface had in 0.10.x; if {@code registerMigration} ever loses its default, this
 * test source stops compiling and the build fails — which is the whole point. Note that the SPI
 * compatibility gate cannot cover this: adding a method to an interface is binary-compatible (existing
 * implementors link until it is invoked), so japicmp is silent on exactly this class of change.
 */
@DisplayName("SPI source compatibility: FlowExecutionPlanFactory.registerMigration is additive")
class FlowExecutionPlanFactorySourceCompatTest {

    /** An implementor written against the 0.10.x shape of the interface — nothing added since. */
    private static final class PreV011Factory implements FlowExecutionPlanFactory {

        @Override
        public FlowDefinitionBuilder newDefinition(String definitionName) {
            throw new UnsupportedOperationException("fixture");
        }

        @Override
        public FlowExecutionPlan compile(FlowDefinition definition) {
            throw new UnsupportedOperationException("fixture");
        }
    }

    @Test
    @DisplayName("a pre-0.11 implementor still satisfies the interface")
    void preV011ImplementorStillCompiles() {
        assertThat(new PreV011Factory())
                .as("if this stops compiling, an addition to a stable interface went in without a "
                        + "default and every downstream implementor breaks at their next build")
                .isInstanceOf(FlowExecutionPlanFactory.class);
    }

    @Test
    @DisplayName("and the default refuses rather than silently accepting a transform it will never apply")
    void defaultRefusesRegistration() {
        FlowExecutionPlanFactory factory = new PreV011Factory();
        FlowMigrationState unusedState = new FlowMigrationState(0, "step", new int[0], new String[0], 0, new byte[0]);

        assertThatThrownBy(() -> factory.registerMigration("orders", 1, parked -> unusedState))
                .as("a swallowed registration would surface much later as a saga refused for a reason "
                        + "naming a remedy the operator has already applied")
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
