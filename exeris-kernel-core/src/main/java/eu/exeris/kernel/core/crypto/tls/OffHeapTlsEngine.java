/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.crypto.tls;

import eu.exeris.kernel.core.crypto.openssl.CoreOpenSslLoader;
import eu.exeris.kernel.core.crypto.openssl.CoreSslHandles;
import eu.exeris.kernel.core.crypto.openssl.NativeCipherContext;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.crypto.TlsEngine;
import eu.exeris.kernel.spi.crypto.TlsHandshakeResult;
import eu.exeris.kernel.spi.crypto.TlsPhase;
import eu.exeris.kernel.spi.crypto.TlsStatus;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.crypto.TlsDecryptException;
import eu.exeris.kernel.spi.exceptions.crypto.TlsException;
import eu.exeris.kernel.spi.exceptions.crypto.TlsHandshakeException;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;

import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Core: Zero-allocation TLS engine for plain TCP connections (TLS 1.3 over file descriptor).
 *
 * <h2>Responsibility</h2>
 * <p>Orchestrates three CORE components to implement the {@link TlsEngine} SPI contract:
 * <ul>
 *   <li>{@link CoreSslHandles} — pre-resolved Panama FFM method handles</li>
 *   <li>{@link NativeCipherContext} — refcounted {@code SSL*} pointer lifecycle</li>
 *   <li>{@link TlsStateMachine} — lock-free CAS-based session phase tracking</li>
 * </ul>
 *
 * <h2>The Wall (CORE Compliance)</h2>
 * <p>This class contains <strong>zero</strong> knowledge of QUIC, io_uring, or any
 * Enterprise-tier transport. The only I/O model supported is TLS over a POSIX file
 * descriptor ({@code SSL_set_fd} + {@code SSL_accept}/{@code SSL_connect}).
 *
 * <h2>Zero-Allocation Hot Path</h2>
 * <ul>
 *   <li>{@link #unwrap} and {@link #wrap} pass raw {@code long} addresses to
 *       {@code SSL_read}/{@code SSL_write} — no {@code MemorySegment} wrapper object created.</li>
 *   <li>All pre-allocated singleton results ({@link TlsHandshakeResult#COMPLETE},
 *       {@link TlsHandshakeResult#NEED_UNWRAP}, {@link TlsHandshakeResult#NEED_WRAP}) are
 *       returned by reference — zero heap allocation on the handshake hot path.</li>
 *   <li>ALPN string is read once at handshake completion and cached as a {@code String}
 *       field — {@link #negotiatedProtocol()} is O(1), allocation-free after first call.</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>NOT thread-safe by design, per the {@link TlsEngine} contract. Each carrier / virtual
 * thread owns its own {@code OffHeapTlsEngine} instance. The {@link NativeCipherContext}
 * refcount CAS guards against concurrent close() races only.
 *
 * <h2>JFR-First</h2>
 * <ul>
 *   <li>{@link TlsEngineBindEvent} — emitted once at {@link #bindToFileDescriptor}</li>
 *   <li>{@link TlsPhaseTransitionEvent} — emitted by {@link TlsStateMachine} on each transition</li>
 *   <li>{@link TlsEngineCloseEvent} — emitted once at {@link #close()}</li>
 * </ul>
 *
 * <h2>Closed-state Idempotency</h2>
 * <p>{@link #close()} is guarded by a {@link VarHandle} CAS on {@code closedFlag} —
 * multiple concurrent {@code close()} calls are safe; exactly one will invoke
 * {@link NativeCipherContext#release()}.
 *
 * @since 0.5.0
 * @see TlsEngine
 * @see CoreSslHandles
 * @see NativeCipherContext
 * @see TlsStateMachine
 */
// PMD.CyclomaticComplexity: This class is a direct implementation of the TlsEngine SPI
// contract (10 interface methods) plus mandatory bind/diagnostic/close lifecycle helpers.
// The cumulative CC is an inherent property of faithfully mapping the OpenSSL state machine
// (SSL_accept, SSL_connect, SSL_read, SSL_write, SSL_shutdown, SSL_set_fd, SSL_get_error)
// — not a sign of accidental complexity. Splitting would create artificial,
// non-cohesive helper classes that violate single-responsibility more than the CC rule.
//
// PMD.TooManyMethods: Same rationale — TlsEngine has 10 SPI methods; adding the required
// bind/diagnostic/close surface brings the total to 12 public + 5 private helpers.
// This is the minimum viable surface for a production-grade TLS engine.
@SuppressWarnings({"PMD.CyclomaticComplexity", "PMD.TooManyMethods"})
public final class OffHeapTlsEngine implements TlsEngine {

    // =========================================================================
    // Close guard — VarHandle CAS (mirrors NativeCipherContext pattern)
    // =========================================================================

    private static final VarHandle CLOSED;

    static {
        try {
            CLOSED = MethodHandles.lookup()
                    .findVarHandle(OffHeapTlsEngine.class, "closedFlag", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // =========================================================================
    // SSL operation result constants
    // =========================================================================

    /**
     * Return value of {@code SSL_accept}, {@code SSL_connect}, {@code SSL_do_handshake},
     * and {@code SSL_shutdown} indicating successful completion.
     * Named constant avoids PMD {@code AvoidLiteralsInIfCondition} violations.
     */
    private static final int SSL_SUCCESS = 1;

    /**
     * Sentinel written into {@code closedFlag} by the winning {@link #close()} CAS.
     * Kept separate from {@link #SSL_SUCCESS} to avoid semantic confusion: this value
     * represents a lifecycle state flag, not an OpenSSL ABI return code.
     */
    private static final int CLOSED_MARKER = 1;

    /**
     * L0: Pre-allocated sentinel for the wrap/handshake/close guard path (EX-NET-2001).
     * Thrown by {@link #checkNotClosed()} and {@link #checkActive()} outside of unwrap.
     * Zero heap allocation — stack trace disabled.
     */
    private static final TlsHandshakeException HOT_PATH_WRAP_SENTINEL =
            new TlsHandshakeException();

    /**
     * L0: Pre-allocated sentinel for the unwrap guard path (EX-NET-2003).
     * Thrown by {@link #checkNotClosedForDecrypt()} and {@link #checkActiveForDecrypt()}
     * to honour the one-code-one-schema invariant: decrypt failures carry EX-NET-2003,
     * not EX-NET-2001. Zero heap allocation — stack trace disabled.
     */
    private static final TlsDecryptException DECRYPT_CLOSED_SENTINEL =
            new TlsDecryptException();

    // =========================================================================
    // Instance state — DeclarationOrder: package-private volatile BEFORE private final
    // =========================================================================

    /**
     * Closed flag: 0 = open, 1 = closed.
     * Declared package-private so {@link #CLOSED} VarHandle can locate it via
     * {@code MethodHandles.lookup()} from within the same package.
     * Mutated exclusively via CAS to ensure exactly-once
     * {@link NativeCipherContext#release()} even under concurrent close() races.
     */
    /* default */ volatile int closedFlag = 0; //NOPMD AvoidUsingVolatile — VarHandle CAS; no other option

    private final CoreSslHandles handles;
    private final NativeCipherContext cipherCtx;
    private final TlsStateMachine stateMachine;
    private final boolean serverMode;

    /**
     * Negotiated ALPN protocol string — populated lazily at handshake completion,
     * then immutable. {@code null} until populated; {@code ""} if none was negotiated.
     *
     * <p>Cached here so {@link #negotiatedProtocol()} is O(1) and allocation-free
     * after the first call.
     */
    private volatile String negotiatedAlpn; // null until handshake complete

    /**
     * Wall-clock nanosecond timestamp captured at the start of the TLS handshake
     * ({@link #bindToFileDescriptor}) for JFR {@link TlsHandshakeEvent} duration reporting.
     * Not volatile — written once by the binding thread before any concurrent I/O begins.
     */
    private long handshakeStartNanos;

    // =========================================================================
    // Construction
    // =========================================================================

    /**
     * Creates a new {@code OffHeapTlsEngine} for TLS over TCP.
     *
     * <p>The SSL* handle is allocated immediately via {@code SSL_new(ctxPtr)}.
     * Binding to a file descriptor is deferred to {@link #bindToFileDescriptor(int)}.
     *
     * @param handles    pre-resolved FFM handles from {@link CoreOpenSslLoader#load(java.lang.foreign.Arena)}
     * @param ctxPointer raw {@code SSL_CTX*} address (read-only shared context; caller retains ownership)
     * @param serverMode {@code true} for server role ({@code SSL_accept}),
     *                   {@code false} for client ({@code SSL_connect})
     * @param allocator  {@link MemoryAllocator} used for {@link AllocationHint#SESSION} tracking;
     *                   must be the allocator bound to the current {@code KernelProviders.MEMORY_ALLOCATOR}
     *                   scoped value so that {@code WatermarkManager} can apply backpressure
     * @throws TlsException if {@code SSL_new} returns NULL or the SESSION slab cannot be allocated
     */
    public OffHeapTlsEngine(CoreSslHandles handles, long ctxPointer, boolean serverMode,
                            MemoryAllocator allocator) {
        this.handles      = handles;
        this.cipherCtx    = new NativeCipherContext(handles.handshake(), ctxPointer, allocator);
        this.stateMachine = new TlsStateMachine();
        this.serverMode   = serverMode;
    }

    // =========================================================================
    // Bind — one-time connection wiring
    // =========================================================================

    /**
     * Binds this engine's {@code SSL*} handle to the given POSIX file descriptor
     * via {@code SSL_set_fd}, then transitions the state machine to
     * {@link TlsPhase#HANDSHAKE_IN_PROGRESS}.
     *
     * <p>Must be called <em>before</em> {@link #beginHandshake(LoanedBuffer)}.
     * Emits {@link TlsEngineBindEvent} (JFR-First).
     *
     * @param fileDescriptor POSIX file descriptor of the accepted or connected TCP socket
     * @throws TlsException          if {@code SSL_set_fd} returns an error
     * @throws TlsHandshakeException if the state machine is not in
     *                               {@link TlsPhase#UNINITIALIZED}
     */
    public void bindToFileDescriptor(int fileDescriptor) {
        checkNotClosed();
        long ptr = cipherCtx.retainSslPointer();
        try {
            int result = handles.handshake().invokeSslSetFd(ptr, fileDescriptor);
            if (result != SSL_SUCCESS) {
                stateMachine.forceError();
                throw new TlsException(
                        "SSL_set_fd failed (result=" + result + ") for SSL 0x"
                        + Long.toHexString(ptr) + ", fd=" + fileDescriptor);
            }
            stateMachine.transitionTo(TlsPhase.UNINITIALIZED, TlsPhase.HANDSHAKE_IN_PROGRESS);
            handshakeStartNanos = System.nanoTime();
            TlsEngineBindEvent.emit(ptr, fileDescriptor, serverMode);
            TlsHandshakeEvent.emitStart(ptr, serverMode);
        } finally {
            cipherCtx.release();
        }
    }

    // =========================================================================
    // TlsEngine — handshake
    // =========================================================================

    /**
     * {@inheritDoc}
     *
     * <p><b>Implementation notes:</b>
     * <ul>
     *   <li>Server mode: calls {@code SSL_accept}; one call may not complete the handshake.
     *       Returns {@link TlsStatus#NEED_UNWRAP} when more client data is needed,
     *       {@link TlsStatus#NEED_WRAP} when a flight must be flushed first.</li>
     *   <li>Client mode: calls {@code SSL_connect} and writes the initial
     *       {@code ClientHello} into {@code outbound}.</li>
     *   <li>Both modes: when {@code SSL_get_error} returns {@code SSL_ERROR_WANT_READ},
     *       returns {@link TlsStatus#NEED_UNWRAP}. On {@code SSL_ERROR_WANT_WRITE},
     *       returns {@link TlsStatus#NEED_WRAP}.</li>
     *   <li>On success (result = 1): transitions state to
     *       {@link TlsPhase#HANDSHAKE_COMPLETE}, reads ALPN, then transitions to
     *       {@link TlsPhase#ACTIVE}, and returns {@link TlsStatus#FINISHED}.</li>
     * </ul>
     *
     * <p>The {@code outbound} parameter is reserved for future use — in fd-based sessions
     * outbound handshake bytes are sent directly by the kernel via {@code SSL_set_fd}.
     *
     * @param outbound buffer for outbound handshake bytes (unused in fd-based sessions)
     * @return {@link TlsStatus#FINISHED} on complete, {@link TlsStatus#NEED_UNWRAP} or
     *         {@link TlsStatus#NEED_WRAP} if more steps required
     */
    @Override
    public TlsStatus beginHandshake(LoanedBuffer outbound) {
        checkNotClosed();
        TlsPhase current = stateMachine.phase();
        if (current != TlsPhase.HANDSHAKE_IN_PROGRESS) {
            throw new TlsHandshakeException(
                    "beginHandshake called in wrong state: " + current);
        }

        long ptr = cipherCtx.retainSslPointer();
        try {
            int ret = serverMode
                    ? handles.handshake().invokeSslAccept(ptr)
                    : handles.handshake().invokeSslConnect(ptr);

            if (ret == SSL_SUCCESS) {
                return completeHandshake(ptr);
            }

            int sslErr = handles.ioHandles().invokeGetError(ptr, ret);
            if (sslErr != CoreOpenSslLoader.SSL_ERROR_WANT_READ
                    && sslErr != CoreOpenSslLoader.SSL_ERROR_WANT_WRITE) {
                TlsHandshakeFailureEvent.emit(ptr, serverMode,
                        KernelErrorCodes.EX_NET_2002, "SSL handshake step failed", sslErr);
            }
            return mapSslError(sslErr);

        } finally {
            cipherCtx.release();
        }
    }

    /**
     * Finalises a successful handshake: reads ALPN via {@link AlpnReader} and
     * advances the state machine to {@link TlsPhase#ACTIVE}.
     *
     * <p>Extracted from {@link #beginHandshake} to reduce its cyclomatic complexity.
     *
     * @param ptr raw {@code SSL*} address (caller holds a retain)
     * @return {@link TlsStatus#FINISHED}
     */
    private TlsStatus completeHandshake(long ptr) {
        negotiatedAlpn = AlpnReader.read(ptr, handles.ioHandles(),
                KernelProviders.MEMORY_ALLOCATOR.get());
        String cipherName = CipherNameReader.read(ptr, handles.ioHandles());
        stateMachine.transitionTo(TlsPhase.HANDSHAKE_IN_PROGRESS, TlsPhase.HANDSHAKE_COMPLETE);
        stateMachine.transitionTo(TlsPhase.HANDSHAKE_COMPLETE, TlsPhase.ACTIVE);
        long durationNanos = System.nanoTime() - handshakeStartNanos;
        TlsHandshakeEvent.emitComplete(ptr, serverMode,
                negotiatedAlpn != null ? negotiatedAlpn : "", cipherName, durationNanos, "");
        return TlsStatus.FINISHED;
    }

    // =========================================================================
    // TlsEngine — data transfer (hot path)
    // =========================================================================

    /**
     * {@inheritDoc}
     *
     * <p><b>Zero-Allocation Hot Path:</b>
     * {@code SSL_read} receives the raw {@code long} address from
     * {@link LoanedBuffer#segment()}{@code .address()} — no {@code MemorySegment}
     * wrapper is constructed on this path (identical to the fix applied in
     * {@code OpenSSLProvider.read()} that eliminated {@code NativeMemorySegmentImpl}
     * from the JFR allocation profile).
     */
    @Override
    public TlsStatus unwrap(LoanedBuffer ciphertext, LoanedBuffer plaintext) {
        checkNotClosedForDecrypt();
        checkActiveForDecrypt();

        long sslPtr  = cipherCtx.retainSslPointer();
        try {
            MemorySegment dst = plaintext.segment();
            long dstAddr      = dst.address();
            int  maxLen       = (int) Math.min(dst.byteSize(), Integer.MAX_VALUE);

            // Zero-alloc: raw long address — no NativeMemorySegmentImpl wrapper
            int bytesRead = handles.ioHandles().invokeRead(sslPtr, dstAddr, maxLen);

            if (bytesRead > 0) {
                plaintext.setSize(bytesRead);
                return TlsStatus.OK;
            }

            int sslErr = handles.ioHandles().invokeGetError(sslPtr, bytesRead);

            if (sslErr == CoreOpenSslLoader.SSL_ERROR_ZERO_RETURN) {
                // Peer sent close_notify
                stateMachine.transitionTo(TlsPhase.ACTIVE, TlsPhase.SHUTDOWN_INITIATED);
                return TlsStatus.CLOSED;
            }

            return mapSslError(sslErr);

        } finally {
            cipherCtx.release();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>Zero-Allocation Hot Path:</b>
     * The raw address of {@code plaintext.segment()} is passed directly to
     * {@code SSL_write} — no heap wrapper allocated per call.
     */
    @Override
    public TlsStatus wrap(LoanedBuffer plaintext, LoanedBuffer ciphertext) {
        checkNotClosed();
        checkActive();

        long sslPtr  = cipherCtx.retainSslPointer();
        try {
            MemorySegment src  = plaintext.segment();
            long srcAddr       = src.address();
            int  len           = (int) Math.min(plaintext.size(), Integer.MAX_VALUE);

            // Zero-alloc: raw long address
            int bytesWritten = handles.ioHandles().invokeWrite(sslPtr, srcAddr, len);

            if (bytesWritten > 0) {
                return TlsStatus.OK;
            }

            int sslErr = handles.ioHandles().invokeGetError(sslPtr, bytesWritten);
            return mapSslError(sslErr);

        } finally {
            cipherCtx.release();
        }
    }

    // =========================================================================
    // TlsEngine — state queries
    // =========================================================================

    /** {@inheritDoc} */
    @Override
    public boolean isHandshakeComplete() {
        TlsPhase phase = stateMachine.phase();
        return phase == TlsPhase.ACTIVE
                || phase == TlsPhase.SHUTDOWN_INITIATED
                || phase == TlsPhase.SHUTDOWN_COMPLETE;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The ALPN string is cached after the first successful handshake call —
     * this accessor is O(1) and allocation-free after the handshake is complete.
     * Returns {@code null} before the handshake completes or if no ALPN was negotiated.
     */
    @Override
    public String negotiatedProtocol() {
        String alpn = negotiatedAlpn;
        return (alpn == null || alpn.isEmpty()) ? null : alpn;
    }

    // =========================================================================
    // TlsEngine — shutdown
    // =========================================================================

    /**
     * {@inheritDoc}
     *
     * <p>Calls {@code SSL_shutdown} once. OpenSSL's two-phase shutdown protocol:
     * <ul>
     *   <li>First call (result = 0): local {@code close_notify} was sent;
     *       peer reply not yet received — state remains {@link TlsPhase#SHUTDOWN_INITIATED}.</li>
     *   <li>Second call (result = 1): peer {@code close_notify} also received —
     *       state advances to {@link TlsPhase#SHUTDOWN_COMPLETE}.</li>
     *   <li>Result = -1: fatal error — state forced to {@link TlsPhase#ERROR}.</li>
     * </ul>
     *
     * <p>State transitions:
     * <ul>
     *   <li>{@code ACTIVE} → {@code SHUTDOWN_INITIATED} on first call.</li>
     *   <li>{@code SHUTDOWN_INITIATED} → {@code SHUTDOWN_COMPLETE} when both alerts exchanged.</li>
     * </ul>
     */
    @Override
    public void initiateShutdown(LoanedBuffer outbound) {
        checkNotClosed();
        TlsPhase current = stateMachine.phase();

        // Guard: only meaningful to initiate shutdown from ACTIVE or SHUTDOWN_INITIATED
        if (current != TlsPhase.ACTIVE && current != TlsPhase.SHUTDOWN_INITIATED) {
            // Already shut down or in error — no-op
            return;
        }

        long ptr = cipherCtx.retainSslPointer();
        try {
            if (current == TlsPhase.ACTIVE) {
                stateMachine.transitionTo(TlsPhase.ACTIVE, TlsPhase.SHUTDOWN_INITIATED);
            }
            applyShutdownResult(handles.ioHandles().invokeShutdown(ptr));
        } finally {
            cipherCtx.release();
        }
    }

    /**
     * Applies the result of {@code SSL_shutdown} to the state machine.
     *
     * <p>Extracted from {@link #initiateShutdown} to reduce cyclomatic complexity.
     *
     * @param result return value of {@code SSL_shutdown}: 1 = complete, 0 = partial, &lt;0 = error
     */
    private void applyShutdownResult(int result) {
        if (result == SSL_SUCCESS) {
            stateMachine.transitionTo(TlsPhase.SHUTDOWN_INITIATED, TlsPhase.SHUTDOWN_COMPLETE);
        } else if (result < 0) {
            stateMachine.forceError();
        }
        // result == 0: close_notify sent, awaiting peer — stay in SHUTDOWN_INITIATED
    }


    // =========================================================================
    // State exposure (for transport adapters)
    // =========================================================================

    /**
     * Returns the current {@link TlsPhase} from the underlying state machine.
     *
     * <p>Convenience accessor for transport adapters that need to react to phase
     * changes (e.g., flushing a write queue when {@link TlsPhase#ACTIVE} is reached).
     *
     * @return current phase; never {@code null}
     */
    public TlsPhase phase() {
        return stateMachine.phase();
    }

    /**
     * Returns the raw {@code SSL*} pointer for diagnostic logging and JFR events.
     *
     * <p><b>Must NOT be used to call OpenSSL functions directly.</b>
     * Use {@link NativeCipherContext#retainSslPointer()} for that — this accessor
     * bypasses the reference count and is intended solely for log/JFR correlation.
     *
     * @return raw {@code long} address of the native {@code SSL} structure
     */
    public long sslPointerForDiagnostics() {
        return cipherCtx.sslPointer();
    }

    // =========================================================================
    // TlsEngine — close
    // =========================================================================

    /**
     * {@inheritDoc}
     *
     * <p>Idempotent — guarded by a {@link VarHandle} CAS on {@code closedFlag}.
     * The first call:
     * <ol>
     *   <li>Forces the state machine to the {@link TlsPhase#ERROR} terminal state
     *       (from any non-terminal phase), preventing further I/O dispatch.</li>
     *   <li>Releases the {@link NativeCipherContext} base reference, triggering
     *       {@code SSL_free} when all in-flight retains complete.</li>
     *   <li>Emits {@link TlsEngineCloseEvent} (JFR-First).</li>
     * </ol>
     * Subsequent calls are no-ops.
     */
    @Override
    public void close() {
        if (!CLOSED.compareAndSet(this, 0, CLOSED_MARKER)) {
            return;
        }
        TlsPhase finalPhase = stateMachine.phase();

        // Force terminal state before releasing SSL*
        // (prevents any concurrent I/O that somehow holds a retain from re-entering active logic)
        if (finalPhase != TlsPhase.CLOSED && finalPhase != TlsPhase.ERROR) {
            stateMachine.forceError();
        }

        long ptr = cipherCtx.sslPointer(); // diagnostic only — no retain
        boolean graceful = finalPhase == TlsPhase.SHUTDOWN_COMPLETE;

        cipherCtx.close(); // drops base reference → SSL_free when refCount hits 0
        TlsEngineCloseEvent.emit(ptr, graceful, finalPhase.name());
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /**
     * Maps an {@code SSL_get_error} result code to a {@link TlsStatus}.
     *
     * <p>Only the three "retry" codes ({@code SSL_ERROR_WANT_READ},
     * {@code SSL_ERROR_WANT_WRITE}) are mapped to non-error statuses.
     * All other codes are treated as requiring a handshake retry or are considered
     * transient during the handshake phase and return {@link TlsStatus#NEED_HANDSHAKE}.
     */
    private TlsStatus mapSslError(int sslErrorCode) {
        return switch (sslErrorCode) {
            case CoreOpenSslLoader.SSL_ERROR_WANT_READ  -> TlsStatus.NEED_UNWRAP;
            case CoreOpenSslLoader.SSL_ERROR_WANT_WRITE -> TlsStatus.NEED_WRAP;
            default -> TlsStatus.NEED_HANDSHAKE;
        };
    }

    /**
     * Guards against calling I/O methods on a closed engine.
     *
     * @throws TlsHandshakeException if {@code closedFlag} == 1
     */
    private void checkNotClosed() {
        if ((int) CLOSED.getAcquire(this) == CLOSED_MARKER) {
            throw HOT_PATH_WRAP_SENTINEL;
        }
    }

    /**
     * Guards against calling I/O methods in a non-active phase.
     *
     * @throws TlsHandshakeException if the session is not in {@link TlsPhase#ACTIVE}
     */
    private void checkActive() {
        if (stateMachine.phase() != TlsPhase.ACTIVE) {
            throw HOT_PATH_WRAP_SENTINEL;
        }
    }

    /**
     * Guards the unwrap (decrypt) path against a closed engine.
     * Throws {@link TlsDecryptException} ({@code EX-NET-2003}) to preserve
     * the one-code-one-schema invariant for Glass-Box binary decoders.
     *
     * @throws TlsDecryptException if {@code closedFlag} == 1
     */
    private void checkNotClosedForDecrypt() {
        if ((int) CLOSED.getAcquire(this) == CLOSED_MARKER) {
            throw DECRYPT_CLOSED_SENTINEL;
        }
    }

    /**
     * Guards the unwrap (decrypt) path against a non-active phase.
     * Throws {@link TlsDecryptException} ({@code EX-NET-2003}) to preserve
     * the one-code-one-schema invariant for Glass-Box binary decoders.
     *
     * @throws TlsDecryptException if the session is not in {@link TlsPhase#ACTIVE}
     */
    private void checkActiveForDecrypt() {
        if (stateMachine.phase() != TlsPhase.ACTIVE) {
            throw DECRYPT_CLOSED_SENTINEL;
        }
    }
}
