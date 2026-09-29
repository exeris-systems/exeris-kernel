/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.crypto.tls;

import eu.exeris.kernel.core.crypto.ArenaLoanedBuffer;
import eu.exeris.kernel.spi.exceptions.crypto.TlsHandshakeException;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link OffHeapTlsEngine#expectPeer} routes each kind of identity to the OpenSSL call that checks
 * it, and only that one: a DNS name to the host check with its flags and to the server name
 * indication, an address to the IP check and nowhere else.
 */
@DisplayName("L1: OffHeapTlsEngine.expectPeer — identity routing and guards")
class OffHeapTlsEngineExpectPeerTest {

    private static final MemoryAllocator ALLOC = new MemoryAllocator() {
        @Override
        public LoanedBuffer allocate(AllocationHint hint) {
            return ArenaLoanedBuffer.allocateOwning(hint, 64);
        }

        @Override public LoanedBuffer allocateNetwork(int bytes)         { return allocate(AllocationHint.MEDIUM); }
        @Override public LoanedBuffer allocateCarrierSlab(int index)     { return allocate(AllocationHint.MEDIUM); }
        @Override public LoanedBuffer allocateInfrastructure(long bytes) {
            return ArenaLoanedBuffer.allocateOwning(AllocationHint.MEDIUM, (int) Math.max(bytes, 64));
        }
        @Override public MemoryStats stats()                             { return MemoryStats.zero(); }
        @Override public void close()                                    { /* stateless */ }
    };

    private RecordingOpenSsl openSsl;

    @BeforeEach
    void setUp() {
        openSsl = new RecordingOpenSsl();
    }

    private OffHeapTlsEngine client() {
        OffHeapTlsEngine engine = new OffHeapTlsEngine(openSsl.handles(), 0x1234L, false, ALLOC);
        openSsl.forget();
        return engine;
    }

    @Test
    @DisplayName("a DNS name sets the host flags, the host with its explicit length, and SNI")
    void dnsNameGoesToHostCheckAndSni() {
        try (OffHeapTlsEngine engine = client()) {
            engine.expectPeer(TlsPeerIdentity.of("Service.Example.TEST."));

            assertThat(openSsl.calls()).containsExactly(
                    "SSL_get0_param",
                    "X509_VERIFY_PARAM_set_hostflags:0x24",
                    "X509_VERIFY_PARAM_set1_host:" + "service.example.test".length(),
                    "SSL_ctrl:55:0");
        }
    }

    @Test
    @DisplayName("an IPv4 literal sets a 4-byte IP check and sends no SNI")
    void ipv4GoesToIpCheckOnly() {
        try (OffHeapTlsEngine engine = client()) {
            engine.expectPeer(TlsPeerIdentity.of("127.0.0.1"));

            assertThat(openSsl.calls()).containsExactly("SSL_get0_param", "X509_VERIFY_PARAM_set1_ip:4");
        }
    }

    @Test
    @DisplayName("a bracketed IPv6 literal sets a 16-byte IP check and sends no SNI")
    void ipv6GoesToIpCheckOnly() {
        try (OffHeapTlsEngine engine = client()) {
            engine.expectPeer(TlsPeerIdentity.of("[::1]"));

            assertThat(openSsl.calls()).containsExactly("SSL_get0_param", "X509_VERIFY_PARAM_set1_ip:16");
        }
    }

    @Test
    @DisplayName("OpenSSL refusing the host fails closed and empties the error queue")
    void refusedHostFailsClosed() {
        try (OffHeapTlsEngine engine = client()) {
            openSsl.set1HostResult = 0;

            assertThatThrownBy(() -> engine.expectPeer(TlsPeerIdentity.of("localhost")))
                    .isInstanceOf(TlsHandshakeException.class)
                    .satisfies(e -> assertThat(((TlsHandshakeException) e).rawArgs())
                            .containsExactly(-1, TlsFailureDetail.PEER_IDENTITY_REJECTED));
            assertThat(openSsl.count("ERR_clear_error")).isEqualTo(1);
            assertThat(openSsl.count("SSL_ctrl:55:0")).as("no SNI after a refused host").isZero();
        }
    }

    @Test
    @DisplayName("OpenSSL refusing the server name fails closed")
    void refusedSniFailsClosed() {
        try (OffHeapTlsEngine engine = client()) {
            openSsl.ctrlResult = 0L;

            assertThatThrownBy(() -> engine.expectPeer(TlsPeerIdentity.of("localhost")))
                    .isInstanceOf(TlsHandshakeException.class);
            assertThat(openSsl.count("ERR_clear_error")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("OpenSSL refusing the address fails closed")
    void refusedAddressFailsClosed() {
        try (OffHeapTlsEngine engine = client()) {
            openSsl.set1IpResult = 0;

            assertThatThrownBy(() -> engine.expectPeer(TlsPeerIdentity.of("127.0.0.1")))
                    .isInstanceOf(TlsHandshakeException.class);
            assertThat(openSsl.count("ERR_clear_error")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a second identity is refused")
    void secondCallIsRefused() {
        try (OffHeapTlsEngine engine = client()) {
            engine.expectPeer(TlsPeerIdentity.of("localhost"));

            assertThatThrownBy(() -> engine.expectPeer(TlsPeerIdentity.of("other.invalid")))
                    .isInstanceOf(TlsHandshakeException.class);
        }
    }

    @Test
    @DisplayName("a server engine refuses an identity")
    void serverEngineRefuses() {
        try (OffHeapTlsEngine engine = new OffHeapTlsEngine(openSsl.handles(), 0x1234L, true, ALLOC)) {
            assertThatThrownBy(() -> engine.expectPeer(TlsPeerIdentity.of("localhost")))
                    .isInstanceOf(TlsHandshakeException.class);
            assertThat(openSsl.count("SSL_get0_param")).isZero();
        }
    }

    @Test
    @DisplayName("an engine already bound refuses an identity")
    void boundEngineRefuses() {
        try (OffHeapTlsEngine engine = client()) {
            engine.notifyBound();

            assertThatThrownBy(() -> engine.expectPeer(TlsPeerIdentity.of("localhost")))
                    .isInstanceOf(TlsHandshakeException.class);
            assertThat(openSsl.count("SSL_get0_param")).isZero();
        }
    }

    @Test
    @DisplayName("a closed engine refuses an identity")
    void closedEngineRefuses() {
        OffHeapTlsEngine engine = client();
        engine.close();

        assertThatThrownBy(() -> engine.expectPeer(TlsPeerIdentity.of("localhost")))
                .isInstanceOf(TlsHandshakeException.class);
        assertThat(openSsl.count("SSL_get0_param")).isZero();
    }
}
