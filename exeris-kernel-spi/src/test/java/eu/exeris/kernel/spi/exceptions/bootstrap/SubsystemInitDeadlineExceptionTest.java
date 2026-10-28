/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.exceptions.bootstrap;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L0 Contract: {@link SubsystemInitDeadlineException} code and rawArgs binary layout.
 *
 * <h2>Binary Layout Pinned</h2>
 * <pre>
 * index 0 → String  subsystemName
 * index 1 → long    deadlineMs
 * </pre>
 *
 * @see SubsystemInitDeadlineException
 */
@DisplayName("L0: SubsystemInitDeadlineException — code and rawArgs Binary Layout")
class SubsystemInitDeadlineExceptionTest {

    @Test
    @DisplayName("error code is EX-BOOT-0003")
    void errorCodeIsBoot0003() {
        SubsystemInitDeadlineException ex = new SubsystemInitDeadlineException("memory", 300_000L, null);

        assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_BOOT_0003).isEqualTo("EX-BOOT-0003");
    }

    @Test
    @DisplayName("rawArgs[0]=subsystemName, [1]=deadlineMs, with no cause")
    void layoutWithoutCause() {
        SubsystemInitDeadlineException ex = new SubsystemInitDeadlineException("persistence", 200L, null);

        assertThat(ex.rawArgs()).containsExactly("persistence", 200L);
        assertThat(ex.subsystemName()).isEqualTo("persistence");
        assertThat(ex.deadlineMs()).isEqualTo(200L);
        assertThat(ex.getCause()).isNull();
    }

    @Test
    @DisplayName("rawArgs keep the same layout when the initialize() throwable is the cause")
    void layoutWithCause() {
        RuntimeException root = new IllegalStateException("interrupted during handshake");
        SubsystemInitDeadlineException ex = new SubsystemInitDeadlineException("transport", 5_000L, root);

        assertThat(ex.rawArgs()).containsExactly("transport", 5_000L);
        assertThat(ex.getCause()).isSameAs(root);
    }
}
