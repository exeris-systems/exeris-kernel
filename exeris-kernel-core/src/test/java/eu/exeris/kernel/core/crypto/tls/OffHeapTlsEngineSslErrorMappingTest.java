/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.crypto.tls;

import static org.assertj.core.api.Assertions.assertThat;

import eu.exeris.kernel.core.crypto.openssl.CoreOpenSslLoader;
import eu.exeris.kernel.spi.crypto.TlsStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pure unit coverage for {@link OffHeapTlsEngine#mapSslError(int)} — the
 * {@code SSL_get_error} → {@link TlsStatus} contract. No OpenSSL/FFM required.
 *
 * <p>Regression lock for the phantom-handshake busy-spin: terminal codes
 * ({@code SSL_ERROR_SSL}, {@code SSL_ERROR_SYSCALL}, and any unknown code) MUST map
 * to {@link TlsStatus#CLOSED} so the transport layer tears the fd down, rather than
 * {@link TlsStatus#NEED_HANDSHAKE} (the prior behaviour that re-stepped the handshake
 * on every level-triggered read of a half-open/garbage probe connection).
 */
@DisplayName("L0: OffHeapTlsEngine.mapSslError — SSL_get_error → TlsStatus contract")
class OffHeapTlsEngineSslErrorMappingTest {

    @Test
    @DisplayName("WANT_READ → NEED_UNWRAP (non-terminal: more ciphertext needed)")
    void wantReadMapsToNeedUnwrap() {
        assertThat(OffHeapTlsEngine.mapSslError(CoreOpenSslLoader.SSL_ERROR_WANT_READ))
                .isEqualTo(TlsStatus.NEED_UNWRAP);
    }

    @Test
    @DisplayName("WANT_WRITE → NEED_WRAP (non-terminal: outbound flight must flush)")
    void wantWriteMapsToNeedWrap() {
        assertThat(OffHeapTlsEngine.mapSslError(CoreOpenSslLoader.SSL_ERROR_WANT_WRITE))
                .isEqualTo(TlsStatus.NEED_WRAP);
    }

    @Test
    @DisplayName("SSL_ERROR_SSL → CLOSED (fatal protocol error is terminal, not retryable)")
    void sslErrorIsTerminal() {
        assertThat(OffHeapTlsEngine.mapSslError(CoreOpenSslLoader.SSL_ERROR_SSL))
                .isEqualTo(TlsStatus.CLOSED);
    }

    @Test
    @DisplayName("SSL_ERROR_SYSCALL → CLOSED (I/O error / unexpected peer EOF is terminal)")
    void syscallErrorIsTerminal() {
        assertThat(OffHeapTlsEngine.mapSslError(CoreOpenSslLoader.SSL_ERROR_SYSCALL))
                .isEqualTo(TlsStatus.CLOSED);
    }

    @Test
    @DisplayName("SSL_ERROR_ZERO_RETURN → CLOSED (clean peer close is terminal)")
    void zeroReturnIsTerminal() {
        assertThat(OffHeapTlsEngine.mapSslError(CoreOpenSslLoader.SSL_ERROR_ZERO_RETURN))
                .isEqualTo(TlsStatus.CLOSED);
    }

    @Test
    @DisplayName("unknown SSL_get_error code → CLOSED (fail-safe terminal, never spin)")
    void unknownCodeIsTerminal() {
        assertThat(OffHeapTlsEngine.mapSslError(4242))
                .isEqualTo(TlsStatus.CLOSED);
    }
}
