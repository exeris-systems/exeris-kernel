/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.spi.crypto.TlsEngine;
import eu.exeris.kernel.spi.crypto.TlsStatus;
import eu.exeris.kernel.spi.memory.LoanedBuffer;

import java.io.IOException;
import java.lang.foreign.MemorySegment;

/**
 * Package-private pending-write record held in {@link NativeTcpStream}'s
 * outbound MPSC queue.
 *
 * <p>Extracted from {@link NativeTcpStream} in v0.8 Sprint 3 (QA-016) as the
 * third seam of the stream's God-class decomposition. Carries the plaintext
 * payload, optionally a TLS-wrapped ciphertext buffer (lazily produced on
 * first drain attempt for TLS streams), and tracks offsets across partial
 * socket writes.
 *
 * <p>Construction takes a {@link TlsContext} (engine + lock + ciphertext placeholder) and a
 * {@link TryWriter} dispatcher so the stream's socket-backend selection stays
 * encapsulated in {@link NativeTcpStreamPlainSocketIo}; this class never
 * touches the underlying file descriptor directly.
 */
// plainBuffer/cipherBuffer reset to null after close() to release the reference.
@SuppressWarnings("PMD.NullAssignment")
final class NativeTcpStreamPendingWrite implements AutoCloseable {

    private final TlsContext tlsContext;
    private final TryWriter tryWriter;
    private LoanedBuffer plainBuffer;
    private final int plainLength;
    private int plainOffset;
    private LoanedBuffer cipherBuffer;
    private int cipherLength;
    private int cipherOffset;
    // True once wrap() has run for this write. On the fd-owner path cipherBuffer stays null
    // (wrap writes straight to the socket), so idempotency cannot key off cipherBuffer — a second
    // prepareCipher() would otherwise re-invoke wrap() and re-send the same plaintext.
    private boolean cipherPrepared;

    /**
     * TLS wrap dependency surface for a single stream. {@code tlsLock} must be
     * the same monitor used by the stream for {@code unwrap} so wrap/unwrap
     * cannot interleave on the same {@link TlsEngine}.
     *
     * <p>{@code ciphertextPlaceholder} is the stream's reused empty ({@code size()==0})
     * buffer. In fd-owner BIO mode — the only mode {@code NativeTcpStream} accepts —
     * {@link TlsEngine#wrap} writes ciphertext straight to the kernel socket and never
     * touches its ciphertext argument, so the same placeholder used by ingress unwrap is
     * passed to {@code wrap} on egress instead of allocating a per-write cipher buffer.
     */
    /* default */ record TlsContext(TlsEngine tlsEngine, Object tlsLock,
                                    LoanedBuffer ciphertextPlaceholder) {
    }

    /**
     * Socket-write dispatcher abstraction. The stream binds this to its
     * configured plain-socket backend (seam or NIO fallback) so this class is
     * independent of {@link java.nio.channels.SocketChannel} and
     * {@link eu.exeris.kernel.core.transport.syscall.SyscallHandles}.
     */
    /* default */ @FunctionalInterface interface TryWriter {
        int tryWrite(MemorySegment source, int offset, int length) throws IOException;
    }

    /* default */ NativeTcpStreamPendingWrite(LoanedBuffer plainBuffer,
                                              int plainLength,
                                              TlsContext tlsContext,
                                              TryWriter tryWriter) {
        this.plainBuffer = plainBuffer;
        this.plainLength = plainLength;
        this.tlsContext = tlsContext;
        this.tryWriter = tryWriter;
        this.plainBuffer.setSize(plainLength);
    }

    /* default */ boolean writePlain() throws IOException {
        int written = tryWriter.tryWrite(plainBuffer.segment(), plainOffset, plainLength - plainOffset);
        if (written > 0) {
            plainOffset += written;
        }
        return plainOffset >= plainLength;
    }

    // CloseResource: ciphertextPlaceholder is borrowed (owned by NativeTcpStream, released in
    // finishCloseIfDrained); it must NOT be closed here. The local is the shared placeholder, never
    // a fresh allocation, so there is no resource for this method to close.
    @SuppressWarnings("PMD.CloseResource")
    /* default */ TlsStatus prepareCipher() {
        if (cipherPrepared) {
            return TlsStatus.OK;
        }
        // fd-owner BIO (the only TLS engine NativeTcpStream accepts — buffer-owner engines are
        // rejected at stream construction): wrap() pushes the ciphertext straight to the kernel
        // socket and never touches its ciphertext argument, leaving size()==0. Reuse the per-stream
        // empty placeholder instead of allocating plainLength+overhead per write (mirrors the ingress
        // unwrap placeholder) — removes the dominant per-write egress LoanedBuffer/ReleaseAction churn.
        LoanedBuffer cipher = tlsContext.ciphertextPlaceholder();
        TlsStatus status;
        synchronized (tlsContext.tlsLock()) {
            status = tlsContext.tlsEngine().wrap(plainBuffer, cipher);
        }
        if (status != TlsStatus.OK) {
            return status;
        }
        if (cipher.size() > 0) {
            // Unreachable in fd-owner BIO: a buffer-owner engine would write ciphertext into the
            // shared placeholder, which must never be retained or sent. Fail loud, never corrupt.
            throw new IllegalStateException(
                    "fd-owner TLS wrap produced ciphertext in the shared placeholder (size="
                            + cipher.size() + "); buffer-owner engines are unsupported here");
        }
        cipherPrepared = true;
        return TlsStatus.OK;
    }

    /* default */ boolean writeCipher() throws IOException {
        if (cipherLength == 0) {
            return true;
        }
        int written = tryWriter.tryWrite(cipherBuffer.segment(), cipherOffset, cipherLength - cipherOffset);
        if (written > 0) {
            cipherOffset += written;
        }
        return cipherOffset >= cipherLength;
    }

    /* default */ long bytesWritten() {
        return cipherBuffer != null ? cipherOffset : plainOffset;
    }

    @Override
    public void close() {
        if (cipherBuffer != null) {
            cipherBuffer.close();
            cipherBuffer = null;
        }
        if (plainBuffer != null) {
            plainBuffer.close();
            plainBuffer = null;
        }
    }
}
