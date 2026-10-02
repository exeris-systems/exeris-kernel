/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.bootstrap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCK: verifies bootstrap failure-policy behaviour — whether a missing dependency fails
 * fast under selector {@code ALL}, whether {@code DEGRADE} tolerates an optional
 * subsystem's failure, and whether {@code FAIL_FAST} aborts startup on one.
 *
 * <p>This class drives no bootstrap scenario itself: each hook below reports its own
 * outcome as a {@code boolean}, and the corresponding {@code @Test} only asserts that the
 * value is {@code true}. The contract is proved only to the extent that a binding's hook
 * implementation actually exercises the named scenario against its own orchestrator — a
 * hook that returns a hardcoded {@code true} satisfies this TCK without exercising
 * anything.
 */
public abstract class AbstractFailurePolicyTck {

    /**
     * Creates the contract; subclasses report the fail-fast, degrade and abort outcomes via
     * {@link #missingDependencyFailsInAllSelector()}, {@link #degradeContinuesOnOptionalFailure()}
     * and {@link #failFastAbortsOnOptionalFailure()}.
     *
     * @since 0.5
     */
    public AbstractFailurePolicyTck() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /**
     * Reports whether a missing dependency causes bootstrap to fail immediately even when
     * the failure-policy selector is {@code ALL}.
     *
     * @return {@code true} if a missing dependency fails fast under selector {@code ALL}
     * @throws Exception if driving the scenario against the implementation's orchestrator fails
     */
    protected abstract boolean missingDependencyFailsInAllSelector() throws Exception;

    /**
     * Reports whether the {@code DEGRADE} failure policy allows startup to continue past an
     * optional subsystem's failure.
     *
     * @return {@code true} if startup continues past an optional subsystem's failure under
     *         {@code DEGRADE}
     * @throws Exception if driving the scenario against the implementation's orchestrator fails
     */
    protected abstract boolean degradeContinuesOnOptionalFailure() throws Exception;

    /**
     * Reports whether the {@code FAIL_FAST} failure policy aborts startup when an optional
     * subsystem fails.
     *
     * @return {@code true} if startup aborts on an optional subsystem's failure under
     *         {@code FAIL_FAST}
     * @throws Exception if driving the scenario against the implementation's orchestrator fails
     */
    protected abstract boolean failFastAbortsOnOptionalFailure() throws Exception;

    @Test
    @DisplayName("selector=ALL: missing dependency must fail-fast")
    void missingDependencyMustFailFast() throws Exception {
        assertThat(missingDependencyFailsInAllSelector()).isTrue();
    }

    @Test
    @DisplayName("DEGRADE: optional subsystem failure is tolerated")
    void degradeAllowsOptionalFailure() throws Exception {
        assertThat(degradeContinuesOnOptionalFailure()).isTrue();
    }

    @Test
    @DisplayName("FAIL_FAST: optional subsystem failure aborts startup")
    void failFastAbortsOnOptionalFailureContract() throws Exception {
        assertThat(failFastAbortsOnOptionalFailure()).isTrue();
    }
}

