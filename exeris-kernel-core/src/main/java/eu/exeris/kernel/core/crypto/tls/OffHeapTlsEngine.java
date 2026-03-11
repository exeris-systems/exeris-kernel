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
 * Core: Zero-allocation, protocol-agnostic TLS engine (TLS 1.3 record layer).
 *
 * <h2>Responsibility</h2>
 * <p>Orchestrates three CORE components to implement the {@link TlsEngine} SPI contract:
 * <ul>
 *   <li>{@link CoreSslHandles} — pre-resolved Panama FFM method handles</li>
 *   <li>{@link NativeCipherContext} — refcounted {@code SSL*} pointer lifecycle</li>
 *   <li>{@link TlsStateMachine} — lock-free CAS-based session phase tracking</li>
 * </ul>
 *
 * <h2>Protocol Agnosticism (The Wall — CORE Compliance)</h2>
 * <p>This class has <strong>zero</strong> knowledge of BIO wiring, file descriptors,
 * {@code io_uring}, QUIC, or any transport-specific I/O model.
 * The caller (Community or Enterprise tier) is responsible for:
 * <ol>
 *   <li>Creating the {@code SSL*} via {@code SSL_new(ctxPtr)}.</li>
 *   <li>Attaching the appropriate BIO — either via {@code SSL_set_fd} (Community / TCP fd-owner)
 *       or via {@code BIO_new(BIO_s_mem())} + {@code SSL_set_bio} (Enterprise / Memory-BIO for
 *       TCP and QUIC/DTLS).</li>
 *   <li>Constructing this engine — the constructor allocates the {@code SSL*} handle
 *       via {@code SSL_new(ctxPtr)} but leaves the engine in
 *       {@link TlsPhase#UNINITIALIZED}.</li>
 *   <li>Calling {@link #notifyBound()} after BIO wiring is complete — this advances
 *       the state machine to {@link TlsPhase#HANDSHAKE_IN_PROGRESS}.</li>
 * </ol>
 *
 * <h2>Zero-Allocation Hot Path</h2>
 * <ul>
 *   <li>{@link #unwrap} and {@link #wrap} pass raw {@code long} addresses to
 *       {@code SSL_read}/{@code SSL_write} — no {@code MemorySegment} wrapper object created.</li>
 *   <li>All pre-allocated singleton results ({@link TlsStatus#FINISHED},
 *       {@link TlsStatus#NEED_UNWRAP}, {@link TlsStatus#NEED_WRAP}) are
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
 *   <li>{@link TlsEngineBindEvent} — emitted once from {@link #notifyBound()} after BIO wiring
 *       completes (transition from {@link TlsPhase#UNINITIALIZED} to
 *       {@link TlsPhase#HANDSHAKE_IN_PROGRESS})</li>
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
// PMD.CyclomaticComplexity: TlsEngine has 10 SPI methods plus mandatory lifecycle helpers.
// PMD.TooManyMethods: Same rationale — minimum viable surface for a production-grade TLS engine.
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
     * Wall-clock nanosecond timestamp captured at {@link #notifyBound()} for JFR
     * {@link TlsHandshakeEvent} duration reporting.
     * Not volatile — written once by the binding thread before any concurrent I/O begins.
     */
    private long handshakeStartNanos;

    // =========================================================================
    // Construction
    // =========================================================================

    /**
     * Creates a new {@code OffHeapTlsEngine}.
     *
     * <p>The {@code SSL*} handle is allocated immediately via {@code SSL_new(ctxPtr)}.
     * The engine is left in {@link TlsPhase#UNINITIALIZED} — BIO wiring is the
     * caller's responsibility. Call {@link #notifyBound()} after attaching the
     * appropriate BIO to advance the engine to
     * {@link TlsPhase#HANDSHAKE_IN_PROGRESS}.
     *
     * @param handles    pre-resolved FFM handles from {@link CoreOpenSslLoader#load(java.lang.foreign.Arena)}
     * @param ctxPointer raw {@code SSL_CTX*} address (read-only shared context; caller retains ownership)
     * @param serverMode {@code true} for server role ({@code SSL_accept}),
     *                   {@code false} for client ({@code SSL_connect})
     * @param allocator  {@link MemoryAllocator} used for {@link AllocationHint#SESSION} tracking
     * @throws TlsException if {@code SSL_new} returns {@code NULL}
     * @throws eu.exeris.kernel.spi.exceptions.memory.MemoryExhaustedException
     *         ({@code EX-MEM-1001}) if the {@link AllocationHint#SESSION} slab cannot be
     *         satisfied by the {@code WatermarkManager}
     * @throws IllegalStateException if the provided {@link MemoryAllocator} has been closed
     */
    public OffHeapTlsEngine(CoreSslHandles handles, long ctxPointer, boolean serverMode,
                            MemoryAllocator allocator) {
        this.handles      = handles;
        this.cipherCtx    = new NativeCipherContext(handles.handshake(), ctxPointer, allocator);
        this.stateMachine = new TlsStateMachine();
        this.serverMode   = serverMode;
    }

    // =========================================================================
    // Bind notification — transport-agnostic BIO readiness signal
    // =========================================================================

    /**
     * Notifies this engine that the caller has finished BIO wiring and the TLS
     * handshake may begin.
     *
     * <p>The caller (Community or Enterprise tier) is responsible for attaching
     * the appropriate BIO to the {@code SSL*} handle <em>before</em> calling this
     * method:
     * <ul>
     *   <li><b>Community (TCP fd-owner):</b> {@code SSL_set_fd(sslPtr, socketFd)}</li>
     *   <li><b>Enterprise (Memory-BIO):</b> {@code BIO_new_pair} + {@code SSL_set_bio}
     *       for both plain TCP and QUIC/DTLS streams.</li>
     * </ul>
     *
     * <p>Transitions the state machine from {@link TlsPhase#UNINITIALIZED} to
     * {@link TlsPhase#HANDSHAKE_IN_PROGRESS} and emits {@link TlsEngineBindEvent}
     * (JFR-First).
     *
     * @throws TlsHandshakeException if the engine is not in {@link TlsPhase#UNINITIALIZED}
     *                               or has already been closed
     */
    public void notifyBound() {
        checkNotClosed();
        try {
            stateMachine.transitionTo(TlsPhase.UNINITIALIZED, TlsPhase.HANDSHAKE_IN_PROGRESS);
        } catch (TlsHandshakeException e) {
            // Illegal bind attempt (e.g. called twice) — poison the session so that
            // subsequent beginHandshake() calls correctly see a terminal ERROR phase.
            stateMachine.forceError();
            throw e;
        }
        handshakeStartNanos = System.nanoTime();

        long ptr = cipherCtx.sslPointer();
        TlsEngineBindEvent.emit(ptr, serverMode);
        TlsHandshakeEvent.emitStart(ptr, serverMode);
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
        // PMD.CloseResource: MemoryAllocator is a ScopedValue-bound reference whose lifecycle
        // is owned by the caller scope — closing it here would destroy the caller's allocator.
        @SuppressWarnings("PMD.CloseResource")
        final MemoryAllocator allocator;
        try {
            allocator = KernelProviders.MEMORY_ALLOCATOR.get();
        } catch (IllegalStateException e) {
            throw new TlsHandshakeException(
                    "MemoryAllocator ScopedValue is not bound during TLS handshake completion", e);
        }
        negotiatedAlpn = AlpnReader.read(ptr, handles.ioHandles(), allocator);
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
     * wrapper is constructed on this path.
     *
     * <p><b>Transport note:</b> the {@code ciphertext} parameter role depends on the
     * BIO wired by the caller tier before constructing this engine. With a fd-owner BIO
     * (Community), {@code SSL_read} pulls from the kernel socket buffer and the
     * {@code ciphertext} buffer is unused. With a Memory-BIO (Enterprise), the caller
     * must have pre-filled the read-BIO before invoking this method.
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

            int bytesRead = handles.ioHandles().invokeRead(sslPtr, dstAddr, maxLen);

            if (bytesRead > 0) {
                plaintext.setSize(bytesRead);
                return TlsStatus.OK;
            }

            int sslErr = handles.ioHandles().invokeGetError(sslPtr, bytesRead);

            if (sslErr == CoreOpenSslLoader.SSL_ERROR_ZERO_RETURN) {
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
     *
     * <p><b>Transport note:</b> the {@code ciphertext} parameter role depends on the
     * BIO wired by the caller tier before constructing this engine. With a fd-owner BIO
     * (Community), {@code SSL_write} pushes to the kernel socket buffer and the
     * {@code ciphertext} buffer is unused. With a Memory-BIO (Enterprise), ciphertext
     * is drained from the write-BIO by the caller after this method returns.
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

        if (current != TlsPhase.ACTIVE && current != TlsPhase.SHUTDOWN_INITIATED) {
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
        TlsPhase phaseAtEntry = stateMachine.phase();

        if (phaseAtEntry == TlsPhase.SHUTDOWN_COMPLETE) {
            stateMachine.transitionTo(TlsPhase.SHUTDOWN_COMPLETE, TlsPhase.CLOSED);
        } else if (phaseAtEntry != TlsPhase.CLOSED && phaseAtEntry != TlsPhase.ERROR) {
            stateMachine.forceError();
        }

        long ptr = cipherCtx.sslPointer();
        boolean graceful    = phaseAtEntry == TlsPhase.SHUTDOWN_COMPLETE;

        TlsPhase finalPhase = stateMachine.phase();
        cipherCtx.close();
        TlsEngineCloseEvent.emit(ptr, graceful, finalPhase.name());
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    /**
     * Maps an {@code SSL_get_error} result code to a {@link TlsStatus}.
     *
     * <p>Only the two "retry" codes ({@code SSL_ERROR_WANT_READ},
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
