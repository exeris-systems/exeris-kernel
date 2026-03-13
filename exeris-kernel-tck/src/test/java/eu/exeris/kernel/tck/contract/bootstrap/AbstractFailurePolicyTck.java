/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.tck.contract.bootstrap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TCK contract for bootstrap failure-policy behaviour.
 */
public abstract class AbstractFailurePolicyTck {

    /** @return true when missing dependency fails immediately even with selector=ALL. */
    protected abstract boolean missingDependencyFailsInAllSelector() throws Exception;

    /** @return true when DEGRADE continues startup after optional subsystem failure. */
    protected abstract boolean degradeContinuesOnOptionalFailure() throws Exception;

    /** @return true when FAIL_FAST aborts startup on optional subsystem failure. */
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

