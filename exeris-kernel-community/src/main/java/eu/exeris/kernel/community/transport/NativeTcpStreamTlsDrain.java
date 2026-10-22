/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.spi.crypto.TlsStatus;
import eu.exeris.kernel.spi.exceptions.transport.TransportException;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Package-private TLS egress drain for {@link NativeTcpStream}: turns the outcome of one
 * {@code SSL_write} (through {@link NativeTcpStreamPendingWrite#prepareCipher()}) into a drain
 * verdict, so the stream's own drain loop stays protocol-agnostic.
 *
 * <h2>Stall rules</h2>
 * <p>The fd-owner BIO reports {@link TlsStatus#NEED_WRAP} when the socket send buffer is full. That
 * is back pressure, not a failure: the head write stays queued and the drain reports a stall; the
 * caller arms write interest and the reactor retries when the socket is writable again.
 * <ul>
 *   <li><b>The retry is the same call.</b> The engine context runs without partial-write and
 *       moving-write-buffer modes, so OpenSSL accepts a retry only with the identical buffer address
 *       and length ({@code SSL_R_BAD_WRITE_RETRY} otherwise, which surfaces as a fatal status). A
 *       stalled head entry is therefore never re-cut, merged with a neighbour, resized or
 *       reallocated, and {@link NativeTcpStreamPendingWrite} keeps its prepared flag unset until the
 *       record layer has accepted every byte.</li>
 *   <li><b>{@link TlsStatus#NEED_UNWRAP} is a stall that does not arm write interest.</b> The
 *       engine is waiting for the peer, and a writable socket would only make the reactor spin. The
 *       carrier's ingress loop flushes the queue after each readable event. The stall is recorded under the
 *       TLS lock in the wrap's own critical section, and an ingress flush that finds the slot held
 *       leaves a retry request for the holder, so neither the record nor the flush can be missed.</li>
 *   <li><b>Every other status aborts.</b> The caller holds the outbound slot, so the abort drains
 *       the queue under it and never re-enters {@code close()}; finishing the close is left to the
 *       code that releases the slot.</li>
 * </ul>
 *
 * <p><b>Allocation:</b> none on the stall or the success path.
 * <p><b>Thread confinement:</b> {@link #drain} runs only on the thread that holds the stream's
 * outbound single-consumer slot; {@link #stalledOnRead()} is readable from any thread.
 * <p><b>Ownership:</b> owns no resource; the pending write and the stream's flags belong to the
 * stream.
 */
final class NativeTcpStreamTlsDrain {

    /** The head write was fully handed to the socket. */
    /* default */ static final int DRAIN_OK = 1;
    /** The head write stays queued and a later flush retries it. */
    /* default */ static final int DRAIN_STALLED = 2;

    /**
     * Test-only: runs on every stall, on the thread that holds the outbound slot, so a test waits on an
     * event instead of polling for a full send buffer. It must be idempotent, because it runs once per
     * stalled attempt, and it must never be set outside tests. {@code null} outside tests.
     */
    /* default */ static final AtomicReference<Runnable> ON_WRITE_STALL_FOR_TEST = new AtomicReference<>();

    private final String engineName;
    private final AtomicBoolean remoteClosed;
    private final AtomicBoolean closed;
    private final AbortSink abortSink;
    private final AtomicBoolean stalledOnRead;
    private final AtomicBoolean retryAfterIngress = new AtomicBoolean();

    /** Aborts the stream after an unrecoverable send failure. */
    /* default */ @FunctionalInterface interface AbortSink {
        void abort(boolean peerGone);
    }

    /* default */ NativeTcpStreamTlsDrain(String engineName, AtomicBoolean remoteClosed, AtomicBoolean closed,
                                          AtomicBoolean stalledOnRead, AbortSink abortSink) {
        this.stalledOnRead = stalledOnRead;
        this.engineName = engineName;
        this.remoteClosed = remoteClosed;
        this.closed = closed;
        this.abortSink = abortSink;
    }

    /**
     * Whether the head write waits for the peer rather than for socket space. While it does, the
     * stream does not arm write interest.
     *
     * @return {@code true} after a {@link TlsStatus#NEED_UNWRAP} stall, until the next attempt
     */
    /* default */ boolean stalledOnRead() {
        return stalledOnRead.get();
    }

    /**
     * Records that an ingress batch may have satisfied a write that waits for the peer, so a thread that
     * holds the outbound slot right now retries it once it releases the slot.
     *
     * @return {@code true} if a write waits for the peer; the caller then attempts the flush itself
     */
    /* default */ boolean markRetryAfterIngress() {
        if (!stalledOnRead.get()) {
            return false;
        }
        retryAfterIngress.set(true);
        return true;
    }

    /**
     * Takes the retry request an ingress batch left while the slot was held.
     *
     * @return {@code true} if the slot holder that just released the slot has to flush again
     */
    /* default */ boolean consumeRetryAfterIngress() {
        return retryAfterIngress.getAndSet(false) && stalledOnRead.get();
    }

    /**
     * Attempts the head write once.
     *
     * @param pending the head of the outbound queue; the caller holds the outbound slot
     * @return {@link #DRAIN_OK} or {@link #DRAIN_STALLED}
     * @throws TransportException ({@code EX-NET-4002}) if the engine reported an unrecoverable
     *                            status; the stream is already aborting
     */
    /* default */ int drain(NativeTcpStreamPendingWrite pending) {
        TlsStatus status = pending.prepareCipher();
        if (status != TlsStatus.NEED_UNWRAP && stalledOnRead.get()) {
            stalledOnRead.set(false);
        }
        if (status == TlsStatus.OK) {
            return tryDrainCipherWrite(pending) ? DRAIN_OK : DRAIN_STALLED;
        }
        if (status == TlsStatus.NEED_WRAP || status == TlsStatus.NEED_UNWRAP) {
            Runnable hook = ON_WRITE_STALL_FOR_TEST.get();
            if (hook != null) {
                hook.run();
            }
            return DRAIN_STALLED;
        }
        // An unrecoverable wrap status must not leave the queued write stuck: hasPendingData()
        // would stay true and hang teardown, and a nested close() would release the slot this
        // frame still holds.
        abortSink.abort(remoteClosed.get());
        throw TransportException.sendFailure(engineName, pending.bytesWritten(), null);
    }

    private boolean tryDrainCipherWrite(NativeTcpStreamPendingWrite pending) {
        try {
            return pending.writeCipher();
        } catch (IOException e) {
            if (closed.get()) {
                return true;
            }
            abortSink.abort(remoteClosed.get());
            throw TransportException.sendFailure(engineName, pending.bytesWritten(), e);
        }
    }
}
