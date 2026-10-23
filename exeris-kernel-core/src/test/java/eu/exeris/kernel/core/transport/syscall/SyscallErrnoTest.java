/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.transport.syscall;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("L1: SyscallErrno retryability classification")
class SyscallErrnoTest {

    @Test
    @DisplayName("unknown and unavailable errno values are not treated as retryable")
    void unknownAndUnavailableErrnosAreNotRetryable() {
        assertThat(SyscallErrno.isRetryable(-1)).isFalse();
        assertThat(SyscallErrno.isRetryable(0)).isFalse();
        assertThat(SyscallErrno.isRetryable(12345)).isFalse();
    }

    @Test
    @DisplayName("known retryable socket errno values remain retryable")
    void knownRetryableErrnosRemainRetryable() {
        assertThat(SyscallErrno.isRetryable(4)).isTrue();
        assertThat(SyscallErrno.isRetryable(11)).isTrue();
        assertThat(SyscallErrno.isRetryable(35)).isTrue();
        assertThat(SyscallErrno.isRetryable(10_004)).isTrue();
        assertThat(SyscallErrno.isRetryable(10_035)).isTrue();
        assertThat(SyscallErrno.isRetryable(10_036)).isTrue();
    }

    @Test
    @DisplayName("a broken pipe and the platform's connection reset say the peer ended the connection")
    void brokenPipeAndConnectionResetArePeerResets() {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.startsWith("windows")) {
            assertThat(SyscallErrno.isPeerReset(10_054)).as("WSAECONNRESET").isTrue();
            assertThat(SyscallErrno.isPeerReset(10_053)).as("WSAECONNABORTED").isTrue();
            return;
        }
        int connectionReset = os.startsWith("mac") ? 54 : 104;
        assertThat(SyscallErrno.isPeerReset(32)).as("EPIPE").isTrue();
        assertThat(SyscallErrno.isPeerReset(connectionReset)).as("ECONNRESET").isTrue();
    }

    @Test
    @DisplayName("a retryable, timed-out, unknown or unavailable errno does not say the peer ended the connection")
    void otherErrnosAreNotPeerResets() {
        for (int errno : new int[] {11, 4, 35, 0, -1, 110, 10_035}) {
            assertThat(SyscallErrno.isPeerReset(errno)).as("errno " + errno).isFalse();
        }
    }
}
