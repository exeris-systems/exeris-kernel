/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.crypto.SocketChannelFdAccess;
import eu.exeris.kernel.core.transport.syscall.SyscallErrno;
import eu.exeris.kernel.core.transport.syscall.SyscallHandles;
import eu.exeris.kernel.spi.crypto.TlsEngine;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

/**
 * Package-private plain-socket I/O dispatch for {@link NativeTcpStream}.
 *
 * <p>Extracted from {@link NativeTcpStream} in v0.8 Sprint 3 (QA-016) as the
 * second seam of the stream's God-class decomposition. Owns the
 * {@code core-socket-seam} ↔ {@code nio-fallback} backend selection and the
 * Panama {@code recv}/{@code send} syscall invocations.
 *
 * <p>Selection rule (see {@link Backend#resolve}): the core-socket seam is
 * eligible only when (a) no TLS engine is present (TLS implies socket-owner
 * ownership of the fd), (b) the {@link SyscallHandles} indicate plain-socket
 * support on this platform, and (c) the channel's underlying file descriptor
 * is resolvable via {@link SocketChannelFdAccess}.
 */
final class NativeTcpStreamPlainSocketIo {

    private static final int POSIX_SOCKET_IO_FLAGS = 0;

    private NativeTcpStreamPlainSocketIo() {
        // package-private static utility — never instantiated.
    }

    /**
     * Plain-socket backend selected per stream. The stream caches the chosen
     * backend at construction and dispatches every {@code read}/{@code write}
     * through it.
     */
    /* default */ enum Backend { // pkg-private — referenced only by NativeTcpStream

        CORE_SOCKET_SEAM("core-socket-seam"),
        NIO_FALLBACK("nio-fallback");

        private final String configValue;

        Backend(String configValue) {
            this.configValue = configValue;
        }

        /* default */ boolean usesCoreSocketSeam() {
            return this == CORE_SOCKET_SEAM;
        }

        /* default */ String configValue() {
            return configValue;
        }

        /* default */ static Backend resolve(SocketChannel channel,
                                             TlsEngine tlsEngine,
                                             SyscallHandles socketHandles) {
            if (tlsEngine != null
                    || socketHandles == null
                    || !socketHandles.supportsPlainSocketIo()
                    || socketHandles.hasIoctlsocket()) {
                return NIO_FALLBACK;
            }
            return SocketChannelFdAccess.canResolveFd(channel) ? CORE_SOCKET_SEAM : NIO_FALLBACK;
        }
    }

    /**
     * Reads up to {@code maxBytes} into {@code target} via the core-socket
     * seam {@code recv} syscall. Returns the byte count on success, {@code -1}
     * on remote close (POSIX {@code recv} → 0), {@code 0} on retryable errno
     * ({@code EAGAIN}/{@code EWOULDBLOCK}), or throws {@link IOException} on
     * any other syscall failure.
     */
    // Panama invokeExact() may throw Throwable per FFM contract; rewrap as IOException.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    /* default */ static int seamRead(SyscallHandles socketHandles,
                                      int plainSocketHandle,
                                      MemorySegment target,
                                      int maxBytes,
                                      long streamId) throws IOException {
        try {
            // Zero-alloc ingress (PERF-072): the carrier always reads the full loaned slab
            // (maxBytes == segment byteSize), so asSlice(0, maxBytes) would allocate a fresh
            // NativeMemorySegmentImpl wrapper identical to target on every read. Pass target
            // directly in that case — recv() takes the length as a separate argument, so the
            // wrapper is a pure no-op. Slice only for a genuine sub-range.
            MemorySegment dst = maxBytes == target.byteSize() ? target : target.asSlice(0, maxBytes);
            long result = (long) socketHandles.recv().invokeExact(
                    plainSocketHandle,
                    dst,
                    (long) maxBytes,
                    POSIX_SOCKET_IO_FLAGS);
            if (result > 0) {
                return (int) result;
            }
            if (result == 0) {
                return -1;
            }
            int errno = SyscallErrno.currentSocketErrno(socketHandles);
            if (SyscallErrno.isRetryable(errno)) {
                return 0;
            }
            throw new IOException("Core socket recv() failed with errno=" + errno + " for stream " + streamId);
        } catch (IOException ex) {
            throw ex;
        } catch (Throwable ex) {
            throw new IOException("Core socket recv() invocation failed for stream " + streamId, ex);
        }
    }

    /**
     * Writes {@code length} bytes from {@code source} starting at {@code offset}
     * via the core-socket seam {@code send} syscall. Returns the byte count on
     * success, {@code 0} on retryable errno, or falls back to the NIO channel
     * {@code write} on any non-{@link IOException} Throwable from the seam.
     */
    // Panama invokeExact() may throw Throwable per FFM contract; NIO fallback covers non-IO failures.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    /* default */ static int seamWriteWithNioFallback(SyscallHandles socketHandles,
                                                      int plainSocketHandle,
                                                      SocketChannel channel,
                                                      MemorySegment source,
                                                      int offset,
                                                      int length,
                                                      long streamId) throws IOException {
        try {
            long result = (long) socketHandles.send().invokeExact(
                    plainSocketHandle,
                    source.asSlice(offset, length),
                    (long) length,
                    POSIX_SOCKET_IO_FLAGS);
            if (result >= 0) {
                return (int) result;
            }
            int errno = SyscallErrno.currentSocketErrno(socketHandles);
            if (SyscallErrno.isRetryable(errno)) {
                return 0;
            }
            throw new IOException("Core socket send() failed with errno=" + errno + " for stream " + streamId);
        } catch (IOException ex) {
            throw ex;
        } catch (Throwable _) {
            MemorySegment slice = source.asSlice(offset, length);
            ByteBuffer sourceBuffer = slice.asByteBuffer();
            return channel.write(sourceBuffer);
        }
    }

    /**
     * NIO-fallback read path used when the seam is not selected.
     */
    /* default */ static int nioFallbackRead(SocketChannel channel,
                                             MemorySegment target,
                                             int maxBytes) throws IOException {
        // Zero-alloc ingress (PERF-072): elide the redundant asSlice wrapper when the caller
        // reads the full segment. The guard is also semantically required here — a ByteBuffer
        // over the full segment would have capacity == byteSize, letting channel.read() overrun
        // maxBytes; only elide when they are equal (slice and full segment are then identical).
        MemorySegment readInto = maxBytes == target.byteSize() ? target : target.asSlice(0, maxBytes);
        ByteBuffer targetBuffer = readInto.asByteBuffer();
        targetBuffer.clear();
        return channel.read(targetBuffer);
    }

    /**
     * NIO-fallback write path used when the seam is not selected.
     */
    /* default */ static int nioFallbackWrite(SocketChannel channel,
                                              MemorySegment source,
                                              int offset,
                                              int length) throws IOException {
        MemorySegment slice = source.asSlice(offset, length);
        ByteBuffer sourceBuffer = slice.asByteBuffer();
        return channel.write(sourceBuffer);
    }
}
