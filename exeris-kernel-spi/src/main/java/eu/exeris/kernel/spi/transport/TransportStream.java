/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.transport;

import eu.exeris.kernel.spi.memory.LoanedBuffer;

import java.lang.foreign.MemorySegment;

/**
 * SPI: A bidirectional byte stream over a {@link TransportConnection}.
 *
 * <h2>Protocol Blindness (The Wall)</h2>
 * <p>Business logic reads and writes bytes via this interface without knowing
 * whether the underlying transport is:
 * <ul>
 *   <li><b>Community:</b> a single TCP connection (1 connection = 1 stream)</li>
 *   <li><b>Enterprise:</b> a multiplexed QUIC stream (N streams per connection)</li>
 * </ul>
 *
 * <h2>Zero-Copy Contract</h2>
 * <p>Both {@link #read} and {@link #write} operate on {@link MemorySegment} slices
 * within a {@link LoanedBuffer}: payload moves between the wire and off-heap memory the
 * caller already holds, never through an intermediate heap array.
 *
 * <p><b>Allocation:</b> zero-alloc is the Enterprise-tier bar; the Community tier is bounded
 * instead (see {@code TransportZeroAllocTck}). On every tier, {@link #read}, {@link #write} and
 * {@link #queueWrite} carry bytes through off-heap memory supplied by the caller and return no
 * new object as their result — but a driver's own I/O plumbing underneath the call may still
 * allocate.
 * <p><b>Thread confinement:</b> owner thread — a stream is owned by exactly one virtual
 * thread (the "1 VT per stream" model), the thread on which the engine invokes
 * {@link StreamHandler#handle(TransportStream)}.
 * <p><b>Ownership:</b> the {@link StreamHandler} that receives a stream closes it;
 * {@link #queueWrite} takes ownership of the loan it is handed on normal return and gives it
 * back to the caller on any throw; segments passed to {@link #read} and {@link #write} stay
 * with the caller throughout.
 *
 * @implSpec Implementations must not copy stream payload between heap and off-heap memory.
 *           A {@link #read} may block the owning virtual thread — which unmounts from its
 *           carrier — but must never pin the carrier thread.
 *           A {@link #write} or {@link #queueWrite} on a closed stream, and a positive-{@code maxBytes}
 *           {@link #read} on one that has no end of stream left to report, throws
 *           {@link eu.exeris.kernel.spi.exceptions.transport.TransportStreamClosedException}, never a
 *           bare {@link IllegalStateException}. A {@link #read} that can still report end of stream
 *           returns {@code -1} instead of throwing. The exception carries {@code EX-NET-4008} when the
 *           transport observed the remote end, or tore the stream down without a local {@code close()}
 *           or {@code reset(long)} request, before the stream was closed through this endpoint, and
 *           {@code EX-NET-4009} when the stream was closed through this endpoint first
 *           ({@code close()}, {@code reset(long)} or the parent connection's {@code close()}).
 *           Every transition that ends the stream — {@code close()}, {@code reset(long)}, the parent
 *           connection's {@code close()}, a send failure that ends it, the peer's reset, or a teardown
 *           by the transport — wakes every thread blocked in {@link #read} or {@link #write} on it. A
 *           woken {@link #read} returns {@code -1} while it can still report end of stream and
 *           otherwise throws the closed-stream signal; a woken {@link #write} throws the closed-stream
 *           signal. Whether the peer's end of its output also ends writes is transport semantics.
 * @implNote A carrier driven by native asynchronous I/O completions writes into pre-registered
 *           slab buffers; the Community carrier reads directly from the underlying connection
 *           into the caller's segment. A future migration of hot-path stream metadata to a
 *           value-class-backed representation would want implementations to avoid identity
 *           operations ({@code ==}, {@code synchronized}) on this interface; no such guard
 *           exists yet, and the Community binding currently synchronizes internally (e.g. to
 *           serialize TLS handshake setup).
 * @since 0.5
 * @see TransportConnection
 * @see StreamHandler
 */
public interface TransportStream extends AutoCloseable {

    /**
     * Reads up to {@code maxBytes} bytes from this stream into the given segment.
     *
     * <p>This call may block the calling virtual thread until data arrives.
     * Returns the number of bytes actually read, or {@code -1} if the stream
     * has been cleanly closed by the remote peer (EOF).
     *
     * <p><b>Non-positive {@code maxBytes}.</b> A {@code maxBytes <= 0} request returns
     * {@code 0} immediately: no I/O is performed and no stream state changes (the stream
     * remains readable, and a remote-close / EOF that has not yet been observed is
     * <em>not</em> consumed — a subsequent positive-{@code maxBytes} read still reports it).
     * This makes a zero/negative request a pure no-op rather than a blocking call, an error,
     * or an EOF probe, so it is safe in a hot read loop that computes its request size
     * from a (possibly drained) budget.
     *
     * <p>The no-op takes precedence over closed state: a non-positive request returns
     * {@code 0} even after this stream has been closed, rather than raising the
     * {@link eu.exeris.kernel.spi.exceptions.transport.TransportStreamClosedException} that a
     * positive-{@code maxBytes} read on a closed stream throws.
     *
     * @param target   off-heap segment to read into (from {@link LoanedBuffer#segment()})
     * @param maxBytes maximum number of bytes to read (must be ≤ {@code target.byteSize()});
     *                 a value {@code <= 0} is a no-op that returns {@code 0}
     * @return number of bytes read, {@code 0} if {@code maxBytes <= 0}, or {@code -1} on EOF
     * @throws eu.exeris.kernel.spi.exceptions.transport.TransportException on I/O failure —
     *         {@code EX-NET-4003} when a TLS handshake this read has to wait for does not
     *         complete within the driver's deadline
     * @throws eu.exeris.kernel.spi.exceptions.transport.TransportStreamClosedException if this stream has been closed —
     *         with the code the interface {@code @implSpec} defines;
     *         a read that can still report end of stream returns {@code -1} instead
     * @implSpec Implementations must not throw on non-positive {@code maxBytes}, and must fill
     *           {@code target} from the transport's receive path with no intermediate heap copy.
     * @implNote A carrier driven by native asynchronous I/O fills the target segment directly
     *           from the completion buffer; the Community carrier reads into it from the
     *           underlying connection.
     */
    int read(MemorySegment target, int maxBytes);

    /**
     * Writes {@code length} bytes from the given segment to this stream.
     *
     * <p>The caller retains ownership of the segment.
     *
     * <p><b>Backpressure.</b> A transport bounds the bytes a stream holds that its peer has not yet
     * taken ({@link TransportConfig#maxQueuedWriteBytes()}). While that bound is reached, this call
     * blocks the calling thread until enough of them drain or the stream ends. It never blocks while
     * holding a monitor, and a transport never blocks its own event-loop thread here: a call made on
     * one queues past the bound instead. With {@link TransportConfig#UNBOUNDED_QUEUED_WRITE_BYTES} it
     * does not block for backpressure. A call that throws after blocking may already have queued a
     * prefix of {@code source}.
     *
     * @param source off-heap segment containing data to send
     * @param length number of bytes to write (must be ≤ {@code source.byteSize()})
     * @throws eu.exeris.kernel.spi.exceptions.transport.TransportException on I/O failure —
     *         {@code EX-NET-4002} when the send itself fails and the peer has not ended the stream,
     *         {@code EX-NET-4003} when a TLS handshake this write has to wait for does not complete
     *         within the driver's deadline
     * @throws eu.exeris.kernel.spi.exceptions.transport.TransportStreamClosedException if this stream has been closed —
     *         with the code the interface {@code @implSpec} defines, including a send that fails
     *         because the peer ended the stream, which reports {@code EX-NET-4008} with the transport
     *         failure ({@code EX-NET-4002}) as its cause
     * @implSpec Implementations must not hold a reference to {@code source} after this call
     *           returns.
     */
    void write(MemorySegment source, int length);

    /**
     * Queues a {@link LoanedBuffer} for transmission on this stream.
     *
     * <p><strong>Ownership — callee-closes-on-success, caller-closes-on-throw:</strong>
     * this method takes ownership of {@code buffer}.
     * <ul>
     *   <li><strong>On normal return</strong> the transport owns the loan and releases it once its
     *       bytes have left the stream's outbound queue, or when the stream ends. The caller MUST NOT
     *       close {@code buffer}.</li>
     *   <li><strong>If this method throws</strong> (including
     *       {@link eu.exeris.kernel.spi.exceptions.transport.TransportStreamClosedException}),
     *       ownership returns to the caller, who MUST close {@code buffer} to avoid leaking it.
     *       Because {@link LoanedBuffer#close()} is idempotent, this caller-close is safe even
     *       if an implementation happened to release the loan while unwinding.</li>
     * </ul>
     * The rule is simply: the callee closes on success, the caller closes on throw. A caller
     * that never frees on exception leaks one buffer per failed write against a transport that
     * releases only on success.
     *
     * <p><strong>Never waits for the peer.</strong> The call returns once the loan is in this
     * stream's outbound order, whatever the peer is reading. It may hand bytes to the network on the
     * calling thread but does not wait for the peer to take them, and the outbound bound of
     * {@link TransportConfig#maxQueuedWriteBytes()} counts these bytes without ever blocking on them.
     * The one wait it may make is for the connection to finish being established, such as a TLS
     * handshake, bounded by the driver's deadline and reported as {@code EX-NET-4003}.
     *
     * <p><strong>The release is the caller's backpressure signal.</strong> The transport releases the
     * loan once its bytes have left the stream's outbound queue, or when the stream ends, and not
     * before. A caller that does not bound the loans it has handed over and not yet seen released
     * holds memory without bound.
     *
     * @param buffer loaned buffer to send; ownership transferred on normal return, retained by
     *               the caller on any thrown exception (the caller must then close it)
     * @param length number of valid bytes in the buffer (from offset 0); must be in
     *               {@code [0, buffer.capacity()]}
     * @throws eu.exeris.kernel.spi.exceptions.transport.TransportException on failure —
     *         {@code EX-NET-4002} when the send itself fails and the peer has not ended the stream,
     *         {@code EX-NET-4003} when a TLS handshake this write has to wait for does not complete
     *         within the driver's deadline; in either case the caller owns and must close
     *         {@code buffer}
     * @throws eu.exeris.kernel.spi.exceptions.transport.TransportStreamClosedException if this stream has been closed —
     *         with the code the interface {@code @implSpec} defines, including a send that fails
     *         because the peer ended the stream, which reports {@code EX-NET-4008} with the transport
     *         failure ({@code EX-NET-4002}) as its cause; the caller owns and must close
     *         {@code buffer}
     * @throws IllegalArgumentException if {@code length} is negative or exceeds
     *         {@code buffer.capacity()} — the caller owns and must close {@code buffer}
     * @apiNote A caller that must not block — a writer holding a lock that other senders wait on, or
     *          an event loop — sends through this method and bounds its own outstanding loans by
     *          their release; {@link #write} may block.
     */
    void queueWrite(LoanedBuffer buffer, int length);

    /**
     * Returns the stream identifier.
     *
     * <p>For QUIC, this is the RFC 9000 stream ID. For TCP, this is a synthetic
     * monotonic identifier assigned by the transport engine.
     *
     * @return stream ID (≥ 0)
     */
    long streamId();

    /**
     * Returns {@code true} if this is a bidirectional stream (read + write).
     *
     * <p>For TCP, always returns {@code true}. For QUIC, depends on stream type.
     *
     * @return {@code true} if bidirectional
     */
    boolean isBidirectional();

    /**
     * Returns {@code true} if this stream was initiated by the endpoint that acted
     * as the <em>client</em> in the underlying connection handshake.
     *
     * <p>This is defined in terms of the connection's client/server role, not
     * locality:
     * <ul>
     *   <li>On a <strong>server-side</strong> engine, {@code true} means the stream
     *       was initiated by the remote peer.</li>
     *   <li>On a <strong>client-side</strong> engine, {@code true} means the stream
     *       was initiated locally.</li>
     * </ul>
     *
     * @return {@code true} if initiated by the client endpoint for this connection
     */
    boolean isClientInitiated();

    /**
     * Returns the parent connection that owns this stream.
     *
     * @return parent connection; never {@code null}
     */
    TransportConnection connection();

    /**
     * Returns {@code true} if this stream has pending outbound data queued
     * via {@link #write} or {@link #queueWrite} that has not yet been flushed.
     *
     * @return {@code true} if data is pending
     */
    boolean hasPendingData();

    /**
     * Closes this stream, releasing any associated resources.
     *
     * <p>For QUIC, signals end-of-stream by sending a STREAM frame with the FIN flag set
     * (per RFC 9000). For TCP, half-closes the output side. Idempotent — multiple calls
     * are safe.
     */
    @Override
    void close();

    /**
     * Forcibly aborts this stream with the given protocol error code, then terminates it.
     *
     * <p>Unlike {@link #close()} (a graceful end-of-stream that drains queued writes), {@code reset}
     * is an <em>abortive</em> termination: any outbound data still queued via {@link #queueWrite}
     * is abandoned (after this call {@link #hasPendingData()} is {@code false}), and the underlying
     * transport's stream-reset mechanism is engaged — implementations map {@code errorCode} to that
     * mechanism (an abortive close for raw TCP; for a transport whose protocol stream <em>is</em> a
     * transport stream, its native stream-reset frame). The code is a caller-supplied {@code long}
     * carrying no transport-specific meaning at this contract level.
     *
     * <p>Abandonment is observable at the <em>peer</em>: outbound data still queued via
     * {@link #queueWrite} at {@code reset} time MUST NOT be delivered to the remote endpoint
     * (in contrast to {@link #close()}, which drains it). A transport that cannot guarantee
     * non-delivery does not provide true abortive reset and should retain the default graceful
     * behavior. The {@code AbstractTransportStreamTck} discriminator that enforces this is opt-in
     * (a binding declares true-reset support); the default {@link #close()}-based implementation is
     * exempt rather than silently treated as conformant.
     *
     * <p>This method exists so callers can abort a single stream <em>polymorphically</em>, without
     * reaching through to a concrete transport type. It is <strong>idempotent</strong> and composes
     * with {@link #close()}: after {@code reset}, a subsequent {@code close()} (or {@code reset})
     * is a no-op, and any further {@link #read}/{@link #write}/{@link #queueWrite} is rejected.
     *
     * <p>The default implementation performs a best-effort graceful {@link #close()} — adequate for
     * transports without a distinct abort primitive. Transports that can signal a true stream reset
     * (abortive TCP close, native protocol stream-reset) <strong>SHOULD</strong> override it.
     *
     * @param errorCode caller-supplied protocol error code (advisory; transport-mapped)
     */
    default void reset(long errorCode) {
        // errorCode is contract for overriders (e.g. a TCP driver maps it to a protocol RST via
        // SO_LINGER 0); the default has no abort primitive and intentionally discards it. CodeQL
        // "useless parameter" here is a false positive — DISMISS it. Do NOT add a read-and-discard
        // line to silence it: that only trades the finding for an "unread local variable" on the
        // discard (the #209 -> follow-up tail-chase). The @param Javadoc documents the discard.
        close();
    }
}
