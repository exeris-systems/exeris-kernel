/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.crypto.TlsEngine;
import eu.exeris.kernel.spi.crypto.TlsStatus;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Locks the fd-owner TLS egress contract for {@link NativeTcpStreamPendingWrite#prepareCipher()}:
 * the per-write cipher buffer is the reused per-stream empty placeholder, so wrapping a queued
 * write allocates <b>nothing</b> (the dominant per-request egress churn this fix removed). A
 * buffer-owner engine — unsupported by {@code NativeTcpStream} — that writes into the shared
 * placeholder must fail loud rather than corrupt the wire.
 */
@DisplayName("NativeTcpStreamPendingWrite — fd-owner TLS egress reuses placeholder (zero per-write alloc)")
final class NativeTcpStreamPendingWriteTest {

    private MemoryAllocator allocator;
    private LoanedBuffer placeholder;

    @BeforeEach
    void setUp() {
        allocator = new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
        placeholder = allocator.allocateInfrastructure(1);
        placeholder.setSize(0);
    }

    @AfterEach
    void tearDown() {
        placeholder.close();
        allocator.close();
    }

    @Test
    @DisplayName("prepareCipher reuses the placeholder and allocates nothing (fd-owner BIO)")
    void prepareCipherAllocatesNothingInFdOwnerMode() {
        FdOwnerTlsEngine engine = new FdOwnerTlsEngine();
        NativeTcpStreamPendingWrite pending = newPendingWrite(engine, "hello-world-payload");

        long before = allocator.stats().allocationCount();
        assertThat(pending.prepareCipher()).isEqualTo(TlsStatus.OK);
        long after = allocator.stats().allocationCount();

        assertThat(after - before).as("prepareCipher must not allocate on the fd-owner egress path").isZero();
        assertThat(engine.wrapCalls).isEqualTo(1);
        assertThat(placeholder.size()).as("placeholder stays empty and reusable").isZero();
        // Idempotent: a second prepareCipher() returns OK WITHOUT re-invoking wrap() (guarded by the
        // cipherPrepared flag). Re-wrapping would re-run SSL_write on the same plaintext — silent wire
        // corruption — so true idempotency must hold independently of the production drain loop.
        assertThat(pending.prepareCipher()).isEqualTo(TlsStatus.OK);
        assertThat(engine.wrapCalls).as("second prepareCipher must not re-invoke wrap").isEqualTo(1);
        pending.close();
    }

    @Test
    @DisplayName("a buffer-owner engine writing into the shared placeholder fails loud, never corrupts")
    void bufferOwnerWrapFailsLoud() {
        NativeTcpStreamPendingWrite pending = newPendingWrite(new BufferOwnerTlsEngine(), "x");
        assertThatThrownBy(pending::prepareCipher)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("buffer-owner engines are unsupported");
        pending.close();
    }

    private NativeTcpStreamPendingWrite newPendingWrite(TlsEngine engine, String payload) {
        byte[] bytes = payload.getBytes(StandardCharsets.US_ASCII);
        LoanedBuffer plain = allocator.allocateNetwork(bytes.length);
        MemorySegment.copy(MemorySegment.ofArray(bytes), 0, plain.segment(), 0, bytes.length);
        NativeTcpStreamPendingWrite.TlsContext ctx =
                new NativeTcpStreamPendingWrite.TlsContext(engine, new Object(), placeholder);
        return new NativeTcpStreamPendingWrite(plain, bytes.length, ctx, (src, off, len) -> len);
    }

    /** Simulates fd-owner BIO: wrap() pushes ciphertext to the socket and leaves the buffer untouched. */
    private static final class FdOwnerTlsEngine implements TlsEngine {
        private int wrapCalls;

        @Override
        public TlsStatus wrap(LoanedBuffer plaintext, LoanedBuffer ciphertext) {
            wrapCalls++;
            return TlsStatus.OK;
        }

        @Override public TlsStatus beginHandshake(LoanedBuffer outbound) {
            return TlsStatus.OK;
        }

        @Override public TlsStatus unwrap(LoanedBuffer ciphertext, LoanedBuffer plaintext) {
            return TlsStatus.OK;
        }

        @Override public boolean isHandshakeComplete() {
            return true;
        }

        @Override public String negotiatedProtocol() {
            return "h2";
        }

        @Override public void initiateShutdown(LoanedBuffer outbound) {
            // no-op
        }

        @Override public void close() {
            // no-op
        }
    }

    /** Simulates an (unsupported) buffer-owner engine writing ciphertext into the buffer. */
    private static final class BufferOwnerTlsEngine implements TlsEngine {
        @Override
        public TlsStatus wrap(LoanedBuffer plaintext, LoanedBuffer ciphertext) {
            ciphertext.setSize(1);
            return TlsStatus.OK;
        }

        @Override public TlsStatus beginHandshake(LoanedBuffer outbound) {
            return TlsStatus.OK;
        }

        @Override public TlsStatus unwrap(LoanedBuffer ciphertext, LoanedBuffer plaintext) {
            return TlsStatus.OK;
        }

        @Override public boolean isHandshakeComplete() {
            return true;
        }

        @Override public String negotiatedProtocol() {
            return "h2";
        }

        @Override public void initiateShutdown(LoanedBuffer outbound) {
            // no-op
        }

        @Override public void close() {
            // no-op
        }
    }
}
