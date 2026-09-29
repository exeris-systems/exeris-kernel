/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.crypto.CommunityTlsEngine;
import eu.exeris.kernel.community.crypto.SocketChannelFdAccess;
import eu.exeris.kernel.spi.crypto.TlsEngine;
import eu.exeris.kernel.spi.crypto.TlsStatus;
import eu.exeris.kernel.spi.exceptions.crypto.TlsHandshakeException;
import eu.exeris.kernel.spi.exceptions.transport.TransportException;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;

import java.nio.channels.SocketChannel;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Package-private TLS handshake state of one {@link NativeTcpStream}: binding the engine to the
 * socket, driving the handshake to completion, and the failure it records when the handshake fails.
 *
 * <p>A handshake failure is recorded under the stream's TLS lock by whichever thread observes it,
 * before the stream closes; {@link #throwRecordedFailure()} then throws it as a
 * {@link TlsHandshakeException} ({@code EX-NET-2001}) built on the throwing thread. No handshake step
 * reaches the engine after a failure is recorded.
 *
 * <p>For a plaintext stream ({@code tlsEngine == null}) every check is a no-op: the handshake is
 * always ready and no failure is ever recorded.
 *
 * <p><b>Allocation:</b> one transient infrastructure buffer per handshake step; nothing once the
 * handshake is ready.
 * <p><b>Thread confinement:</b> none; every method may run on the stream's reactor or on any caller
 * thread. Handshake steps serialise on the stream's TLS lock, and the ready and bound flags are
 * atomic, so a thread that observes the handshake ready observes the engine state that made it so.
 * <p><b>Ownership:</b> owns no buffer beyond a step; the engine, channel and lock belong to the
 * stream.
 */
@SuppressWarnings("PMD.CyclomaticComplexity") // one status dispatch per handshake step, kept in one loop
final class NativeTcpStreamTlsHandshake {

    private static final long HANDSHAKE_TIMEOUT_MILLIS = 10_000L;

    private final String engineName;
    private final TlsEngine tlsEngine;
    private final SocketChannel channel;
    private final MemoryAllocator allocator;
    private final Object tlsLock;
    private final NativeTcpHandshakeBackoff backoff;
    private final AtomicBoolean streamClosed;
    private final Runnable closeStream;
    private final Runnable writeInterestCallback;
    private final AtomicBoolean bound = new AtomicBoolean(false);
    private final AtomicBoolean ready = new AtomicBoolean(false);
    // Written once under tlsLock by the thread whose handshake step returned CLOSED, before it closes
    // the stream; every later closed or end-of-stream report throws it instead.
    @SuppressWarnings("java:S3077") // an immutable record, published once
    private volatile NativeTcpTlsFailure recordedFailure;

    /**
     * Creates the handshake state of one stream; a {@code null} engine makes every check a no-op.
     *
     * @param engineName            the transport engine name reported in a handshake timeout
     * @param tlsEngine             the stream's engine, or {@code null} for a plaintext stream
     * @param channel               the stream's socket, bound to the engine on first use
     * @param allocator             the stream's allocator, for the per-step handshake buffer
     * @param tlsLock               the stream's TLS lock, shared with its wrap and unwrap
     * @param backoff               the pause between two handshake steps of a blocking caller
     * @param streamClosed          the stream's terminal closed flag
     * @param closeStream           closes the stream when a handshake step returns {@code CLOSED}
     * @param writeInterestCallback arms write interest when the handshake needs to send
     */
    /* default */ NativeTcpStreamTlsHandshake(String engineName,
                                              TlsEngine tlsEngine,
                                              SocketChannel channel,
                                              MemoryAllocator allocator,
                                              Object tlsLock,
                                              NativeTcpHandshakeBackoff backoff,
                                              AtomicBoolean streamClosed,
                                              Runnable closeStream,
                                              Runnable writeInterestCallback) {
        this.engineName = engineName;
        this.tlsEngine = tlsEngine;
        this.channel = channel;
        this.allocator = allocator;
        this.tlsLock = tlsLock;
        this.backoff = backoff;
        this.streamClosed = streamClosed;
        this.closeStream = closeStream;
        this.writeInterestCallback = writeInterestCallback;
    }

    /** Records that the carrier has already bound the engine to the socket. */
    /* default */ void markBound() {
        bound.set(true);
    }

    /** Throws the recorded handshake failure, built on this thread, if there is one. */
    /* default */ void throwRecordedFailure() {
        NativeTcpTlsFailure failure = recordedFailure;
        if (failure != null) {
            throw failure.toException();
        }
    }

    /* default */ NativeTcpTlsFailure recordedFailure() {
        return recordedFailure;
    }

    /**
     * Completes the handshake, or advances it one step when {@code blocking} is {@code false}.
     *
     * @param blocking whether to wait for the handshake to finish
     * @return {@code true} once the handshake is complete (always, for a plaintext stream)
     * @throws TlsHandshakeException a blocking caller whose handshake failed ({@code EX-NET-2001})
     * @throws TransportException    a blocking caller whose handshake does not complete within its
     *                               timeout ({@code EX-NET-4003})
     */
    /* default */ boolean ensureReady(boolean blocking) {
        if (tlsEngine == null || ready.get()) {
            return true;
        }

        ensureBound();
        if (markReadyIfHandshakeComplete()) {
            return true;
        }
        boolean handshakeReady = driveHandshake(blocking);
        if (!handshakeReady && blocking) {
            throwRecordedFailure();
        }
        return handshakeReady;
    }

    @SuppressWarnings("PMD.CloseResource") // the engine belongs to the stream, which closes it
    private void ensureBound() {
        if (!(tlsEngine instanceof CommunityTlsEngine communityTlsEngine)) {
            return;
        }
        if (bound.get()) {
            return;
        }
        synchronized (this) {
            if (bound.get()) {
                return;
            }
            communityTlsEngine.bindFileDescriptor(SocketChannelFdAccess.requireFd(channel));
            bound.set(true);
        }
    }

    private boolean driveHandshake(boolean blocking) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(HANDSHAKE_TIMEOUT_MILLIS);
        while (!streamClosed.get()) {
            TlsStatus status = executeHandshakeStep();
            if (status == TlsStatus.FINISHED) {
                return true;
            }
            if (status == TlsStatus.CLOSED) {
                closeStream.run();
                return false;
            }
            if (status == TlsStatus.NEED_WRAP) {
                writeInterestCallback.run();
            }

            if (!blocking) {
                return false;
            }
            if (System.nanoTime() >= deadline) {
                throw TransportException.receiveTimeout(engineName, HANDSHAKE_TIMEOUT_MILLIS);
            }
            backoff.pause();
        }
        return false;
    }

    /**
     * One handshake step under the TLS lock. A step that returns {@code CLOSED} records the engine's
     * failure codes before the lock is released, so no thread can see the stream closed without the
     * reason; once a failure is recorded, no further step reaches the engine.
     */
    private TlsStatus executeHandshakeStep() {
        try (LoanedBuffer outbound = allocator.allocateInfrastructure(1)) {
            synchronized (tlsLock) {
                if (recordedFailure != null) {
                    return TlsStatus.CLOSED;
                }
                if (markReadyIfHandshakeComplete()) {
                    return TlsStatus.FINISHED;
                }
                TlsStatus status = tlsEngine.beginHandshake(outbound);
                if (status == TlsStatus.FINISHED || markReadyIfHandshakeComplete()) {
                    return TlsStatus.FINISHED;
                }
                if (status == TlsStatus.CLOSED) {
                    recordedFailure = NativeTcpTlsFailure.from(tlsEngine);
                }
                return status;
            }
        }
    }

    private boolean markReadyIfHandshakeComplete() {
        if (!tlsEngine.isHandshakeComplete()) {
            return false;
        }
        ready.set(true);
        return true;
    }
}
