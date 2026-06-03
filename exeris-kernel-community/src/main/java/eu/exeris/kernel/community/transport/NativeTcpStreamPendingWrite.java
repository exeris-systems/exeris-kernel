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
import eu.exeris.kernel.spi.memory.MemoryAllocator;

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
 * <p>Construction takes a {@link TlsContext} (engine + lock + allocator) and a
 * {@link TryWriter} dispatcher so the stream's socket-backend selection stays
 * encapsulated in {@link NativeTcpStreamPlainSocketIo}; this class never
 * touches the underlying file descriptor directly.
 */
// plainBuffer/cipherBuffer reset to null after close() to release the reference.
@SuppressWarnings("PMD.NullAssignment")
final class NativeTcpStreamPendingWrite implements AutoCloseable {

    private static final int TLS_WRAP_OVERHEAD_BYTES = 1024;

    private final TlsContext tlsContext;
    private final TryWriter tryWriter;
    private LoanedBuffer plainBuffer;
    private final int plainLength;
    private int plainOffset;
    private LoanedBuffer cipherBuffer;
    private int cipherLength;
    private int cipherOffset;

    /**
     * TLS wrap dependency surface for a single stream. {@code tlsLock} must be
     * the same monitor used by the stream for {@code unwrap} so wrap/unwrap
     * cannot interleave on the same {@link TlsEngine}.
     */
    /* default */ record TlsContext(TlsEngine tlsEngine, Object tlsLock, MemoryAllocator allocator) {
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

    /* default */ TlsStatus prepareCipher() {
        if (cipherBuffer != null) {
            return TlsStatus.OK;
        }
        try (LoanedBuffer cipher = tlsContext.allocator().allocateNetwork(plainLength + TLS_WRAP_OVERHEAD_BYTES)) {
            TlsStatus status;
            synchronized (tlsContext.tlsLock()) {
                status = tlsContext.tlsEngine().wrap(plainBuffer, cipher);
            }
            if (status != TlsStatus.OK) {
                return status;
            }
            if (cipher.size() > 0) {
                cipher.retain();
                cipherBuffer = cipher;
                cipherLength = (int) cipher.size();
            }
            return TlsStatus.OK;
        }
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
