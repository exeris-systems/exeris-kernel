/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.exceptions.transport;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L0 Contract: {@link TransportException} rawArgs binary layouts.
 *
 * <h2>rawArgs Layouts Verified</h2>
 * <pre>
 * EX_NET_4001 (bindFailure):         [String transportName, int port]
 * EX_NET_4002 (sendFailure):         [String transportName, long bytesSent]
 * EX_NET_4003 (receiveTimeout):      [String transportName, long timeoutMs]
 * EX_NET_4004 (bootstrapFailure):    [String transportName, String reason]
 * EX_NET_4005 (engineStartFailure):  [String transportName, int port]
 * </pre>
 *
 * @since 0.5.0
 */
@DisplayName("L0: TransportException rawArgs Binary Layouts")
class TransportExceptionLayoutTest {

    // -----------------------------------------------------------------------
    // EX_NET_4001 — bindFailure
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("bindFailure (EX_NET_4001) — rawArgs[0]=name, rawArgs[1]=port")
    class BindFailure {

        @Test
        @DisplayName("rawArgs[0]=transportName (String), rawArgs[1]=port (int)")
        void rawArgsLayout() {
            TransportException ex = TransportException.bindFailure("HTTP3", 8443, null);
            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_NET_4001);
            Object[] raw = ex.rawArgs();
            assertThat(raw).hasSize(2);
            assertThat(raw[0]).isEqualTo("HTTP3");
            assertThat(raw[1]).isEqualTo(8443);
        }

        @Test
        @DisplayName("port=-1 sentinel is accepted when port is unknown")
        void sentinelPortAccepted() {
            TransportException ex = TransportException.bindFailure("QUIC", -1, null);
            assertThat(ex.rawArgs()[1]).isEqualTo(-1);
        }

        @Test
        @DisplayName("transportName() accessor == rawArgs()[0]")
        void transportNameAccessor() {
            TransportException ex = TransportException.bindFailure("HTTP3", 443, null);
            assertThat(ex.transportName()).isEqualTo("HTTP3");
            assertThat(ex.transportName()).isEqualTo(ex.rawArgs()[0]);
        }

        @Test
        @DisplayName("numericContext() returns port as long")
        void numericContextReturnsPort() {
            TransportException ex = TransportException.bindFailure("TCP", 80, null);
            assertThat(ex.numericContext()).isEqualTo(80L);
        }

        @Test
        @DisplayName("null transportName throws NullPointerException")
        void nullTransportNameThrows() {
            assertThatThrownBy(() -> TransportException.bindFailure(null, 80, null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Cause is preserved by reference")
        void causePreserved() {
            RuntimeException root = new RuntimeException("port in use");
            TransportException ex = TransportException.bindFailure("TCP", 80, root);
            assertThat(ex.getCause()).isSameAs(root);
        }
    }

    // -----------------------------------------------------------------------
    // EX_NET_4002 — sendFailure
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("sendFailure (EX_NET_4002) — rawArgs[0]=name, rawArgs[1]=bytesSent")
    class SendFailure {

        @Test
        @DisplayName("rawArgs[0]=transportName (String), rawArgs[1]=bytesSent (long)")
        void rawArgsLayout() {
            TransportException ex = TransportException.sendFailure("TCP", 1024L, null);
            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_NET_4002);
            Object[] raw = ex.rawArgs();
            assertThat(raw).hasSize(2);
            assertThat(raw[0]).isEqualTo("TCP");
            assertThat(raw[1]).isEqualTo(1024L);
        }

        @Test
        @DisplayName("bytesSent=0 is accepted — zero bytes sent before failure")
        void zeroBytesSent() {
            TransportException ex = TransportException.sendFailure("QUIC", 0L, null);
            assertThat(ex.rawArgs()[1]).isEqualTo(0L);
        }

        @Test
        @DisplayName("rawArgs[1] type is Long — not Integer")
        void rawArgsSlot1IsLong() {
            TransportException ex = TransportException.sendFailure("TCP", 512L, null);
            assertThat(ex.rawArgs()[1]).isInstanceOf(Long.class);
        }
    }

    // -----------------------------------------------------------------------
    // EX_NET_4003 — receiveTimeout
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("receiveTimeout (EX_NET_4003) — rawArgs[0]=name, rawArgs[1]=timeoutMs")
    class ReceiveTimeout {

        @Test
        @DisplayName("rawArgs[0]=transportName (String), rawArgs[1]=timeoutMs (long)")
        void rawArgsLayout() {
            TransportException ex = TransportException.receiveTimeout("QUIC", 5000L);
            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_NET_4003);
            Object[] raw = ex.rawArgs();
            assertThat(raw).hasSize(2);
            assertThat(raw[0]).isEqualTo("QUIC");
            assertThat(raw[1]).isEqualTo(5000L);
        }

        @Test
        @DisplayName("getCause() is null — receiveTimeout has no cause by design")
        void noCause() {
            TransportException ex = TransportException.receiveTimeout("QUIC", 3000L);
            assertThat(ex.getCause()).isNull();
        }

        @Test
        @DisplayName("numericContext() returns timeoutMs as long")
        void numericContextReturnsTimeout() {
            TransportException ex = TransportException.receiveTimeout("QUIC", 10_000L);
            assertThat(ex.numericContext()).isEqualTo(10_000L);
        }
    }

    // -----------------------------------------------------------------------
    // EX_NET_4004 — bootstrapFailure
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("bootstrapFailure (EX_NET_4004) — rawArgs[0]=name, rawArgs[1]=reason (String)")
    class BootstrapFailure {

        @Test
        @DisplayName("rawArgs[0]=transportName (String), rawArgs[1]=reason (String)")
        void rawArgsLayout() {
            TransportException ex = TransportException.bootstrapFailure(
                    "TestTransport", "native library not found", null);
            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_NET_4004);
            Object[] raw = ex.rawArgs();
            assertThat(raw).hasSize(2);
            assertThat(raw[0]).isEqualTo("TestTransport");
            assertThat(raw[1]).isEqualTo("native library not found");
        }

        @Test
        @DisplayName("rawArgs[1] is String — numericContext() must throw IllegalStateException")
        void numericContextThrowsOnStringSlot() {
            TransportException ex = TransportException.bootstrapFailure("T", "reason", null);
            assertThatThrownBy(ex::numericContext)
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("null reason throws NullPointerException")
        void nullReasonThrows() {
            assertThatThrownBy(() -> TransportException.bootstrapFailure("T", null, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    // -----------------------------------------------------------------------
    // EX_NET_4005 — engineStartFailure
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("engineStartFailure (EX_NET_4005) — rawArgs[0]=name, rawArgs[1]=port")
    class EngineStartFailure {

        @Test
        @DisplayName("rawArgs[0]=transportName (String), rawArgs[1]=port (int)")
        void rawArgsLayout() {
            TransportException ex = TransportException.engineStartFailure("HTTP3", 443, null);
            assertThat(ex.errorCode()).isEqualTo(KernelErrorCodes.EX_NET_4005);
            Object[] raw = ex.rawArgs();
            assertThat(raw).hasSize(2);
            assertThat(raw[0]).isEqualTo("HTTP3");
            assertThat(raw[1]).isEqualTo(443);
        }

        @Test
        @DisplayName("port=-1 sentinel is accepted when not applicable")
        void sentinelPortAccepted() {
            TransportException ex = TransportException.engineStartFailure("QUIC", -1, null);
            assertThat(ex.rawArgs()[1]).isEqualTo(-1);
        }
    }

    // -----------------------------------------------------------------------
    // Cross-cutting: inheritance and unchecked contract
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("Cross-cutting: always unchecked (RuntimeException)")
    class InheritanceContract {

        @Test
        @DisplayName("bindFailure is RuntimeException")
        void bindFailureUnchecked() {
            assertThat(TransportException.bindFailure("T", 80, null))
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("receiveTimeout is RuntimeException")
        void receiveTimeoutUnchecked() {
            assertThat(TransportException.receiveTimeout("T", 1L))
                    .isInstanceOf(RuntimeException.class);
        }
    }
}

