/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.exceptions.transport;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L0 Contract: {@link TransportStreamClosedException} — the closed-stream signal and its two codes.
 *
 * @since 0.13
 */
@DisplayName("L0: TransportStreamClosedException")
class TransportStreamClosedExceptionTest {

    @Test
    @DisplayName("the peer-closed signal carries EX-NET-4008, a fixed message and no cause")
    void peerClosedCarriesNet4008() {
        TransportStreamClosedException signal = TransportStreamClosedException.peerClosed();

        assertThat(signal.errorCode()).isEqualTo(KernelErrorCodes.EX_NET_4008).isEqualTo("EX-NET-4008");
        assertThat(signal.isPeerClosed()).isTrue();
        assertThat(signal).hasMessage("Stream closed by peer").isInstanceOf(IllegalStateException.class);
        assertThat(signal.getCause()).isNull();
    }

    @Test
    @DisplayName("the locally closed signal carries EX-NET-4009 and the stream contract's message")
    void locallyClosedCarriesNet4009() {
        TransportStreamClosedException signal = TransportStreamClosedException.locallyClosed();

        assertThat(signal.errorCode()).isEqualTo(KernelErrorCodes.EX_NET_4009).isEqualTo("EX-NET-4009");
        assertThat(signal.isPeerClosed()).isFalse();
        assertThat(signal).hasMessage("Stream is closed").isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("each factory call returns a fresh instance that shares no suppressed exceptions")
    void eachCallReturnsAFreshInstance() {
        TransportStreamClosedException first = TransportStreamClosedException.peerClosed();
        first.addSuppressed(new RuntimeException("close failure"));
        TransportStreamClosedException second = TransportStreamClosedException.peerClosed();

        assertThat(second).isNotSameAs(first);
        assertThat(second.getSuppressed()).isEmpty();
        assertThat(TransportStreamClosedException.locallyClosed())
                .isNotSameAs(TransportStreamClosedException.locallyClosed());
    }
}
