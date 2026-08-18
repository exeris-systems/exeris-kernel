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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Source-compatibility fixture for {@link FlowDefinitionBuilder#version(int)}, added in 0.12 —
 * the sibling of {@code FlowExecutionPlanFactorySourceCompatTest}.
 *
 * <p>The compile half is asserted by this file existing. {@link PreV012Builder} implements only the
 * methods the interface had in 0.11.x; if {@code version} ever loses its default, this test source
 * stops compiling and the build fails. The SPI compatibility gate cannot cover it: adding a method
 * to an interface is binary-compatible, so japicmp is silent on exactly this class of change.
 *
 * <p>The runtime half is the part worth pinning, because the <em>choice</em> of default differs from
 * its two neighbours on purpose. {@code FlowExecutionPlan.definitionVersion()} and
 * {@code FlowExecutionPlanFactory.registerMigration} both ship defaults that return or refuse
 * quietly; this one throws, because a builder that accepted a version and ignored it would produce a
 * v1 definition claiming to be v3 — the confusion ADR-064 exists to prevent. Until this fixture
 * existed, that decision was exercised by nothing but its own Javadoc: Core is the only in-repo
 * implementor and it overrides the default.
 */
@DisplayName("SPI source compatibility: FlowDefinitionBuilder.version is additive and refuses by default")
class FlowDefinitionBuilderSourceCompatTest {

    /** An implementor written against the 0.11.x shape of the interface — nothing added since. */
    private static final class PreV012Builder implements FlowDefinitionBuilder {

        @Override
        public FlowDefinitionBuilder step(String stepId, FlowStepAction action, FlowStepAction compensation) {
            return this;
        }

        @Override
        public FlowDefinitionBuilder transition(int fromStep, int toStep) {
            return this;
        }

        @Override
        public FlowDefinitionBuilder transition(int fromStep, int toStep, String conditionTag) {
            return this;
        }

        @Override
        public FlowDefinitionBuilder timeoutDuration(long durationNanos) {
            return this;
        }

        @Override
        public FlowDefinitionBuilder maxRetries(int maxRetries) {
            return this;
        }

        @Override
        public FlowDefinition build() {
            throw new UnsupportedOperationException("fixture");
        }
    }

    @Test
    @DisplayName("a pre-0.12 implementor still satisfies the interface")
    void preV012ImplementorStillCompiles() {
        assertThat(new PreV012Builder())
                .as("if this stops compiling, an addition to a stable interface went in without a "
                        + "default and every downstream implementor breaks at their next build")
                .isInstanceOf(FlowDefinitionBuilder.class);
    }

    @Test
    @DisplayName("and the default refuses rather than building a v1 definition that claims to be vN")
    void defaultRefusesVersioning() {
        FlowDefinitionBuilder builder = new PreV012Builder();

        assertThatThrownBy(() -> builder.version(3))
                .as("silently ignoring the requested version is the one outcome ADR-064 cannot "
                        + "tolerate: the catalog is keyed by (name, version), so a mislabelled "
                        + "definition parks sagas under a version that describes different steps")
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
