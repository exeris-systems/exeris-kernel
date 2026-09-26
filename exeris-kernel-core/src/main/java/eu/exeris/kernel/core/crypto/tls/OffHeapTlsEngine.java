/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.crypto.tls;

import eu.exeris.kernel.core.crypto.openssl.CoreOpenSslLoader;
import eu.exeris.kernel.core.crypto.openssl.CoreOpenSslRuntime;
import eu.exeris.kernel.core.crypto.openssl.CoreSslHandles;
import eu.exeris.kernel.core.crypto.openssl.NativeCipherContext;
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
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

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
 * <p>This class does not own transport policy or transport lifecycle decisions.
 * It exposes low-level helpers that transport adapters may use for binding
 * (for example fd-owner or Memory-BIO), while keeping orchestration and policy
 * in the caller (Community or Enterprise tier):
 * <ol>
 *   <li>Constructing this engine — the constructor allocates the {@code SSL*} handle
 *       and leaves the engine in {@link TlsPhase#UNINITIALIZED}.</li>
 *   <li>Applying binding policy (for example {@code SSL_set_fd} in Community fd-owner mode).</li>
 *   <li>Calling {@link #notifyBound()} after transport binding is complete — this advances
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
 * <h2>OpenSSL Error Queue</h2>
 * <p>OpenSSL keeps its error queue per OS thread, and {@code SSL_get_error} reports
 * {@code SSL_ERROR_SSL} whenever that queue is non-empty, whichever connection left the entry. So
 * every outcome of {@link #beginHandshake}, {@link #unwrap}, {@link #wrap},
 * {@link #initiateShutdown} and {@link #bindTransportFd} other than a
 * {@code WANT_READ}/{@code WANT_WRITE} retry empties the calling thread's queue before this engine
 * returns or throws. It does so after reading {@code SSL_get_error} and, for a client with an
 * expected peer, {@code SSL_get_verify_result}. No park point lies between those reads and the
 * clear: a virtual thread that unmounted there would clear one carrier's queue and leave another's
 * entry behind. A {@code WANT_*} retry and a successful read or write leave the queue alone, so the
 * record-layer hot path makes no extra downcall.
 *
 * <h2>Peer Verification</h2>
 * <p>A client engine given an identity through {@link #expectPeer} checks the server's certificate
 * against it: the name through {@code X509_VERIFY_PARAM_set1_host}, the address through
 * {@code X509_VERIFY_PARAM_set1_ip}. The chain is verified against the trust of the context the
 * engine was built from. On a context whose verify mode is {@code SSL_VERIFY_PEER}, OpenSSL aborts a
 * handshake that fails the check; on any context, a handshake that OpenSSL completes with a
 * verification result other than {@code X509_V_OK} is refused here, so the engine never becomes
 * {@link TlsPhase#ACTIVE} with a failed verification. A failed handshake leaves its codes on
 * {@link TlsHandshakeFailureCodes}. An engine that is never given an identity checks nothing
 * beyond what its context does, as before.
 *
 * <h2>Closed-state Idempotency</h2>
 * <p>{@link #close()} is guarded by a {@link VarHandle} CAS on {@code closedFlag} —
 * multiple concurrent {@code close()} calls are safe; exactly one will invoke
 * {@link NativeCipherContext#release()}.
 *
 * <p><b>Allocation:</b> zero-alloc on the {@link #wrap}/{@link #unwrap} hot path;
 * allocates once at construction (the native {@code SSL*} handle via
 * {@link NativeCipherContext}) and, at handshake completion, once for the cipher-name
 * string ({@link CipherNameReader}) plus once more only if the negotiated ALPN protocol
 * falls outside the pre-interned {@code h2}/{@code http/1.1}/{@code h3} set
 * ({@link AlpnReader}).
 * <p><b>Thread confinement:</b> owner thread — not thread-safe by design; each
 * carrier or virtual thread owns its own {@code OffHeapTlsEngine} instance. Only the
 * {@link #close()} CAS guard is safe under concurrent calls.
 * <p><b>Ownership:</b> the caller closes the engine via {@link #close()}; the winning
 * {@code close()} call releases the {@link NativeCipherContext} base reference exactly
 * once, freeing the {@code SSL*} handle when no retained reference remains outstanding.
 *
 * @since 0.5
 * @see TlsEngine
 * @see CoreSslHandles
 * @see NativeCipherContext
 * @see TlsStateMachine
 */
// PMD.CyclomaticComplexity: TlsEngine has 10 SPI methods plus mandatory lifecycle helpers.
// PMD.TooManyMethods: Same rationale — minimum viable surface for a production-grade TLS engine.
@SuppressWarnings({"PMD.CyclomaticComplexity", "PMD.TooManyMethods"})
public final class OffHeapTlsEngine implements TlsEngine, TlsHandshakeFailureCodes {

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

    /** Host-check flags: no partial wildcards, and the subject common name is never a host. */
    private static final int HOSTFLAGS = CoreOpenSslLoader.X509_CHECK_FLAG_NO_PARTIAL_WILDCARDS
            | CoreOpenSslLoader.X509_CHECK_FLAG_NEVER_CHECK_SUBJECT;

    /** {@code peerVerificationResult} before a verification result exists. */
    private static final long NO_VERIFICATION_RESULT = -1L;

    /** A native pointer OpenSSL returns when it has nothing to give. */
    private static final long NULL_POINTER = 0L;

    /** Longest verification reason read from OpenSSL's constant strings, NUL included. */
    private static final long MAX_REASON_BYTES = 256L;

    private static final String HANDSHAKE_STEP_FAILED = "SSL handshake step failed";
    private static final String EXPECT_PEER_ON_SERVER = "expectPeer requires a client engine";
    private static final String EXPECT_PEER_AFTER_BIND = "expectPeer requires an engine that is not yet bound";
    private static final String EXPECT_PEER_TWICE = "expected peer is already set";

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
    private final MemoryAllocator allocator;

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

    /** The identity the server must present; {@code null} until {@link #expectPeer} succeeds. */
    private volatile TlsPeerIdentity expectedPeer;

    /** {@link #handshakeFailureSslError()}; written by the thread that drove the failed step. */
    private volatile int failedStepSslError;

    /** {@link #peerVerificationResult()}; written by the thread that drove the handshake. */
    private volatile long verificationResult = NO_VERIFICATION_RESULT;

    // =========================================================================
    // Construction
    // =========================================================================

    /**
     * Creates a new {@code OffHeapTlsEngine}.
     *
    * <p>The {@code SSL*} handle is allocated immediately via {@code SSL_new(ctxPtr)}.
    * The engine is left in {@link TlsPhase#UNINITIALIZED}; callers may apply
    * transport-specific binding (for example {@code SSL_set_fd}) and then call
    * {@link #notifyBound()} to advance the engine to
     * {@link TlsPhase#HANDSHAKE_IN_PROGRESS}.
     *
     * @param handles    pre-resolved FFM handles from {@link CoreOpenSslRuntime#handles()},
     *                   typically obtained from {@link CoreOpenSslLoader#load(java.lang.foreign.Arena)}
     * @param ctxPointer raw {@code SSL_CTX*} address (read-only shared context; caller retains ownership)
     * @param serverMode {@code true} for server role ({@code SSL_accept}),
     *                   {@code false} for client ({@code SSL_connect})
     * @param allocator  {@link MemoryAllocator} used for {@link AllocationHint#SESSION} tracking
     * @throws TlsException ({@code EX-NET-2001}) if {@code SSL_new} returns {@code NULL}
     * @throws eu.exeris.kernel.spi.exceptions.memory.MemoryExhaustedException
     *         ({@code EX-MEM-1001}) if the {@link AllocationHint#SESSION} slab cannot be
     *         satisfied by the {@code WatermarkManager}
     * @throws IllegalStateException if the provided {@link MemoryAllocator} has been closed
     */
    public OffHeapTlsEngine(CoreSslHandles handles, long ctxPointer, boolean serverMode,
                            MemoryAllocator allocator) {
        this.handles      = handles;
        this.cipherCtx    = new NativeCipherContext(handles, ctxPointer, allocator);
        this.stateMachine = new TlsStateMachine();
        this.serverMode   = serverMode;
        this.allocator    = allocator;
    }

    // =========================================================================
    // Bind notification — transport-agnostic BIO readiness signal
    // =========================================================================

    /**
     * Invokes the provided {@code SSL_set_fd} method handle under a reference-counted
     * retain/release, ensuring the underlying {@code SSL*} cannot be freed mid-downcall.
     *
    * <p>This is the sanctioned helper for transport adapters that bind an fd-backed
    * OpenSSL BIO via {@code SSL_set_fd}. Using {@link #sslPointerForDiagnostics()}
    * for actual OpenSSL calls bypasses the refcount contract and risks a
    * use-after-free race.
     *
     * @param sslSetFd       pre-linked method handle for {@code SSL_set_fd(SSL*, int) → int}
     * @param fileDescriptor OS-level socket file descriptor to bind
     * @return raw return value of {@code SSL_set_fd} (1 = success, 0 = failure)
     * @throws TlsHandshakeException ({@code EX-NET-2001}) if the engine is closed or
     *         the downcall throws
     */
    public int bindTransportFd(MethodHandle sslSetFd, int fileDescriptor) {
        checkNotClosed();
        long ptr = cipherCtx.retainSslPointer();
        try {
            int result = (int) sslSetFd.invokeExact(ptr, fileDescriptor);
            if (result != SSL_SUCCESS) {
                clearErrorQueue();
            }
            return result;
        } catch (TlsHandshakeException handshakeException) {
            throw handshakeException;
        } catch (Throwable throwable) { //NOPMD AvoidCatchingGenericException — FFM invokeExact declares Throwable
            throw new TlsHandshakeException("SSL_set_fd invocation failed", throwable);
        } finally {
            cipherCtx.release();
        }
    }

    /**
    * Notifies this engine that the caller has finished transport binding and the TLS
     * handshake may begin.
     *
    * <p>The caller (Community or Enterprise tier) is responsible for applying
    * transport binding to the {@code SSL*} handle <em>before</em> calling this
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
     * @throws TlsHandshakeException ({@code EX-NET-2001}) if the engine is not in
     *         {@link TlsPhase#UNINITIALIZED} or has already been closed
     */
    @Override
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
    // Expected peer
    // =========================================================================

    /**
     * Sets the identity the server certificate must carry. Allowed once, on a client engine, in
     * {@link TlsPhase#UNINITIALIZED}.
     *
     * <p>For a {@link TlsPeerIdentity.DnsName}: host flags {@code NO_PARTIAL_WILDCARDS} and
     * {@code NEVER_CHECK_SUBJECT}, then {@code X509_VERIFY_PARAM_set1_host} with the explicit
     * length, then the server name indication through
     * {@code SSL_ctrl(SSL_CTRL_SET_TLSEXT_HOSTNAME, TLSEXT_NAMETYPE_host_name, name)}. For a
     * {@link TlsPeerIdentity.IpAddress}: {@code X509_VERIFY_PARAM_set1_ip} with its 4 or 16 bytes,
     * and no server name indication. The name or address is staged in a buffer from
     * {@link MemoryAllocator#allocateInfrastructure(long)} that is released before this method
     * returns: OpenSSL copies it. The {@code X509_VERIFY_PARAM} is the session's own, borrowed and
     * never freed.
     *
     * @param peer the identity the server must present
     * @throws TlsHandshakeException ({@code EX-NET-2001}) if this is a server engine, the engine is
     *         bound or closed, an identity was already set, or OpenSSL refuses the identity (detail
     *         {@link TlsFailureDetail#PEER_IDENTITY_REJECTED}, after the thread's error queue is
     *         emptied)
     * @throws NullPointerException if {@code peer} is {@code null}
     */
    public void expectPeer(TlsPeerIdentity peer) {
        Objects.requireNonNull(peer, "peer must not be null");
        checkNotClosed();
        if (serverMode) {
            throw new TlsHandshakeException(EXPECT_PEER_ON_SERVER);
        }
        if (stateMachine.phase() != TlsPhase.UNINITIALIZED) {
            throw new TlsHandshakeException(EXPECT_PEER_AFTER_BIND);
        }
        if (expectedPeer != null) {
            throw new TlsHandshakeException(EXPECT_PEER_TWICE);
        }
        CoreSslHandles.PeerVerificationHandles verification = handles.peerVerification();
        long ptr = cipherCtx.retainSslPointer();
        try {
            long param = verification.invokeGet0Param(ptr);
            if (param == NULL_POINTER) {
                throw identityRejected();
            }
            switch (peer) {
                case TlsPeerIdentity.DnsName dns -> expectName(verification, ptr, param, dns);
                case TlsPeerIdentity.IpAddress address -> expectAddress(verification, param, address);
            }
            expectedPeer = peer;
        } finally {
            cipherCtx.release();
        }
    }

    private void expectName(CoreSslHandles.PeerVerificationHandles verification, long ptr, long param,
                            TlsPeerIdentity.DnsName dns) {
        byte[] name = dns.name().getBytes(StandardCharsets.US_ASCII);
        verification.invokeParamSetHostflags(param, HOSTFLAGS);
        try (LoanedBuffer staged = allocator.allocateInfrastructure(name.length + 1L)) {
            MemorySegment segment = staged.segment();
            MemorySegment.copy(name, 0, segment, ValueLayout.JAVA_BYTE, 0L, name.length);
            segment.set(ValueLayout.JAVA_BYTE, name.length, (byte) 0);
            if (verification.invokeParamSet1Host(param, segment.address(), name.length) != SSL_SUCCESS) {
                throw identityRejected();
            }
            if (verification.invokeCtrl(ptr, CoreOpenSslLoader.SSL_CTRL_SET_TLSEXT_HOSTNAME,
                    CoreOpenSslLoader.TLSEXT_NAMETYPE_HOST_NAME, segment.address()) != SSL_SUCCESS) {
                throw identityRejected();
            }
        }
    }

    private void expectAddress(CoreSslHandles.PeerVerificationHandles verification, long param,
                               TlsPeerIdentity.IpAddress address) {
        byte[] octets = address.octets();
        try (LoanedBuffer staged = allocator.allocateInfrastructure(octets.length)) {
            MemorySegment segment = staged.segment();
            MemorySegment.copy(octets, 0, segment, ValueLayout.JAVA_BYTE, 0L, octets.length);
            if (verification.invokeParamSet1Ip(param, segment.address(), octets.length) != SSL_SUCCESS) {
                throw identityRejected();
            }
        }
    }

    private TlsHandshakeException identityRejected() {
        clearErrorQueue();
        return new TlsHandshakeException(-1, TlsFailureDetail.PEER_IDENTITY_REJECTED);
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code 0} until a handshake step fails, then its {@code SSL_ERROR_*} code; a completed
     *         handshake this engine refuses because verification failed reports
     *         {@code SSL_ERROR_SSL}
     */
    @Override
    public int handshakeFailureSslError() {
        return failedStepSslError;
    }

    /** {@inheritDoc} */
    @Override
    public long peerVerificationResult() {
        return verificationResult;
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
     *   <li>Client mode: calls {@code SSL_connect}.</li>
     *   <li>Both modes: when {@code SSL_get_error} returns {@code SSL_ERROR_WANT_READ},
     *       returns {@link TlsStatus#NEED_UNWRAP}. On {@code SSL_ERROR_WANT_WRITE},
     *       returns {@link TlsStatus#NEED_WRAP}.</li>
     *   <li>On success (result = 1): transitions state to
     *       {@link TlsPhase#HANDSHAKE_COMPLETE}, reads ALPN, then transitions to
     *       {@link TlsPhase#ACTIVE}, and returns {@link TlsStatus#FINISHED}.</li>
     * </ul>
     *
     * <p><b>outbound buffer semantics — BIO mode contract:</b>
     * <ul>
     *   <li><b>fd-owner BIO (Community tier):</b> {@code SSL_accept}/{@code SSL_connect}
     *       writes handshake bytes directly to the kernel socket buffer via the fd BIO.
     *       {@code outbound} is always left empty ({@code size = 0}) on return.
     *       The transport does not need to transmit anything extra.</li>
     *   <li><b>Memory-BIO (Enterprise tier):</b> after this method returns, the Enterprise
     *       tier drains the write-BIO into {@code outbound} using its own
     *       {@code BIO_read} handle, then transmits the contents.
     *       This class has zero knowledge of that BIO drain step.</li>
     * </ul>
     *
     * @param outbound buffer for outbound handshake bytes; always empty in fd-owner BIO mode
     * @return {@link TlsStatus#FINISHED} on complete, {@link TlsStatus#NEED_UNWRAP} or
     *         {@link TlsStatus#NEED_WRAP} if more steps required
     * @throws TlsHandshakeException ({@code EX-NET-2001}) if the engine is closed, or if
     *         called while not in {@link TlsPhase#HANDSHAKE_IN_PROGRESS}
     */
    @Override
    public TlsStatus beginHandshake(LoanedBuffer outbound) {
        checkNotClosed();
        TlsPhase current = stateMachine.phase();
        if (current != TlsPhase.HANDSHAKE_IN_PROGRESS) {
            throw new TlsHandshakeException(
                    "beginHandshake called in wrong state: " + current);
        }

        outbound.setSize(0);

        long ptr = cipherCtx.retainSslPointer();
        try {
            int ret = serverMode
                    ? handles.handshake().invokeSslAccept(ptr)
                    : handles.handshake().invokeSslConnect(ptr);

            if (ret == SSL_SUCCESS) {
                return completeHandshake(ptr);
            }

            int sslErr = handles.ioHandles().invokeGetError(ptr, ret);
            if (isRetry(sslErr)) {
                return mapSslError(sslErr);
            }
            long verify = expectedPeer == null
                    ? NO_VERIFICATION_RESULT
                    : handles.peerVerification().invokeGetVerifyResult(ptr);
            // Self-guard: a fatal code ({@link #mapSslError} → CLOSED) leaves a broken
            // session. Forcing ERROR makes a re-entrant beginHandshake() fail fast on the
            // wrong-state guard rather than re-driving SSL_accept, so engine teardown does
            // not depend solely on the transport calling close() on the CLOSED status.
            return failHandshake(ptr, sslErr, verify);

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
        if (expectedPeer != null) {
            long verify = handles.peerVerification().invokeGetVerifyResult(ptr);
            if (verify != CoreOpenSslLoader.X509_V_OK) {
                return failHandshake(ptr, CoreOpenSslLoader.SSL_ERROR_SSL, verify);
            }
            verificationResult = verify;
        }
        clearErrorQueue();
        negotiatedAlpn = AlpnReader.read(ptr, handles.ioHandles(), allocator);
        String cipherName = CipherNameReader.read(ptr, handles.ioHandles());
        stateMachine.transitionTo(TlsPhase.HANDSHAKE_IN_PROGRESS, TlsPhase.HANDSHAKE_COMPLETE);
        stateMachine.transitionTo(TlsPhase.HANDSHAKE_COMPLETE, TlsPhase.ACTIVE);
        long durationNanos = System.nanoTime() - handshakeStartNanos;
        TlsHandshakeEvent.emitComplete(ptr, serverMode,
                negotiatedAlpn != null ? negotiatedAlpn : "", cipherName, durationNanos, "");
        return TlsStatus.FINISHED;
    }

    /**
     * Ends a handshake that failed or was refused: empties the thread's error queue, records the
     * codes, forces {@link TlsPhase#ERROR} and emits {@link TlsHandshakeFailureEvent}, in that
     * order, so the codes are visible to whoever sees the phase.
     *
     * @param ptr    raw {@code SSL*} address (caller holds a retain)
     * @param sslErr the {@code SSL_get_error} code
     * @param verify the {@code X509_V_*} code, or {@code -1} when there was nothing to verify
     * @return {@link TlsStatus#CLOSED}
     */
    private TlsStatus failHandshake(long ptr, int sslErr, long verify) {
        clearErrorQueue();
        failedStepSslError = sslErr;
        verificationResult = verify;
        stateMachine.forceError();
        TlsHandshakeFailureEvent.emit(ptr, serverMode, KernelErrorCodes.EX_NET_2001,
                failureReason(verify), sslErr, verify);
        return TlsStatus.CLOSED;
    }

    /**
     * OpenSSL's own words for a verification failure, or the generic reason when there was none.
     */
    private String failureReason(long verify) {
        if (verify <= CoreOpenSslLoader.X509_V_OK) {
            return HANDSHAKE_STEP_FAILED;
        }
        long text = handles.peerVerification().invokeVerifyCertErrorString(verify);
        if (text == NULL_POINTER) {
            return HANDSHAKE_STEP_FAILED;
        }
        try {
            return MemorySegment.ofAddress(text).reinterpret(MAX_REASON_BYTES).getString(0L);
        } catch (IllegalArgumentException | IndexOutOfBoundsException unterminated) {
            return HANDSHAKE_STEP_FAILED;
        }
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
     * <p><b>ciphertext buffer semantics — BIO mode contract:</b>
     * <ul>
     *   <li><b>fd-owner BIO (Community tier):</b> {@code SSL_read} pulls ciphertext
     *       directly from the kernel socket buffer via the fd BIO.
     *       The {@code ciphertext} parameter is not read and may be empty.
     *       Decrypted application bytes are written into {@code plaintext}.</li>
     *   <li><b>Memory-BIO (Enterprise tier):</b> the Enterprise tier pre-fills the
     *       read-BIO from {@code ciphertext} using its own {@code BIO_write} handle
     *       <em>before</em> calling this method. This class has zero knowledge of
     *       that BIO-fill step; it only calls {@code SSL_read} and writes into
     *       {@code plaintext}.</li>
     * </ul>
     *
     * @throws TlsDecryptException ({@code EX-NET-2003}) if the engine is closed or not
     *         in {@link TlsPhase#ACTIVE}
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
                clearErrorQueue();
                stateMachine.transitionTo(TlsPhase.ACTIVE, TlsPhase.SHUTDOWN_INITIATED);
                return TlsStatus.CLOSED;
            }

            if (isRetry(sslErr)) {
                return mapSslError(sslErr);
            }
            // Fatal read error (not a clean ZERO_RETURN) on an active session: force ERROR
            // so a subsequent read/renegotiation fails fast on a broken session, mirroring
            // the beginHandshake() self-guard. WANT_READ/WANT_WRITE stay non-terminal.
            clearErrorQueue();
            stateMachine.forceError();
            return TlsStatus.CLOSED;

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
     * <p><b>ciphertext buffer semantics — BIO mode contract:</b>
     * <ul>
     *   <li><b>fd-owner BIO (Community tier):</b> {@code SSL_write} pushes encrypted
     *       bytes directly into the kernel socket buffer via the fd BIO.
     *       The {@code ciphertext} parameter is not written and remains empty ({@code size = 0}).
     *       The transport does not need to drain anything extra.</li>
     *   <li><b>Memory-BIO (Enterprise tier):</b> after this method returns, the
     *       Enterprise tier drains the write-BIO into {@code ciphertext} using its
     *       own {@code BIO_read} handle, then transmits the contents.
     *       This class has zero knowledge of that BIO drain step.</li>
     * </ul>
     *
     * @throws TlsHandshakeException ({@code EX-NET-2001}) if the engine is closed or not
     *         in {@link TlsPhase#ACTIVE}
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
            if (!isRetry(sslErr)) {
                clearErrorQueue();
            }
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
     *
     * <p><b>outbound buffer semantics — BIO mode contract:</b>
     * <ul>
     *   <li><b>fd-owner BIO (Community tier):</b> {@code SSL_shutdown} writes the
     *       {@code close_notify} alert directly to the kernel socket buffer via the
     *       fd BIO. {@code outbound} is always left empty ({@code size = 0}) on return.</li>
     *   <li><b>Memory-BIO (Enterprise tier):</b> after this method returns, the
     *       Enterprise tier drains the write-BIO into {@code outbound} using its own
     *       {@code BIO_read} handle, then transmits the alert bytes.
     *       This class has zero knowledge of that BIO drain step.</li>
     * </ul>
     *
     * @param outbound buffer for the {@code close_notify} alert; always empty in fd-owner BIO mode
     * @throws TlsHandshakeException ({@code EX-NET-2001}) if the engine is closed
     */
    @Override
    public void initiateShutdown(LoanedBuffer outbound) {
        checkNotClosed();
        TlsPhase current = stateMachine.phase();

        if (current != TlsPhase.ACTIVE && current != TlsPhase.SHUTDOWN_INITIATED) {
            return;
        }

        outbound.setSize(0);

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
            clearErrorQueue();
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
     * {@code SSL_ERROR_WANT_WRITE}) are non-terminal — they request more I/O.
     * On a non-blocking fd-owner BIO, "would block" always surfaces as one of those
     * two codes, so <em>any other</em> code (notably {@code SSL_ERROR_SSL} — a fatal
     * protocol error such as non-TLS bytes on the TLS port — and {@code SSL_ERROR_SYSCALL}
     * — an I/O error or unexpected peer EOF) is a terminal condition that maps to
     * {@link TlsStatus#CLOSED}.
     *
     * @apiNote A terminal code must map to {@link TlsStatus#CLOSED} rather than to a
     *          status that requests another handshake step: a half-open or garbage
     *          probe connection stuck in {@code HANDSHAKE_IN_PROGRESS} would otherwise
     *          be re-stepped on every level-triggered read and never torn down.
     *          {@code CLOSED} lets the transport layer ({@code ensureTlsReady}/
     *          {@code unwrap}) tear the fd down after a single real failure.
     * @param sslErrorCode raw {@code SSL_get_error} result code
     * @return {@link TlsStatus#NEED_UNWRAP} for {@code SSL_ERROR_WANT_READ},
     *         {@link TlsStatus#NEED_WRAP} for {@code SSL_ERROR_WANT_WRITE}, or
     *         {@link TlsStatus#CLOSED} for any other code
     */
    /* default */ static TlsStatus mapSslError(int sslErrorCode) {
        return switch (sslErrorCode) {
            case CoreOpenSslLoader.SSL_ERROR_WANT_READ  -> TlsStatus.NEED_UNWRAP;
            case CoreOpenSslLoader.SSL_ERROR_WANT_WRITE -> TlsStatus.NEED_WRAP;
            default -> TlsStatus.CLOSED;
        };
    }

    /**
     * Whether {@code sslErrorCode} asks for more I/O rather than reporting a failure.
     *
     * @param sslErrorCode raw {@code SSL_get_error} result code
     * @return {@code true} for {@code SSL_ERROR_WANT_READ} and {@code SSL_ERROR_WANT_WRITE}
     */
    private static boolean isRetry(int sslErrorCode) {
        return sslErrorCode == CoreOpenSslLoader.SSL_ERROR_WANT_READ
                || sslErrorCode == CoreOpenSslLoader.SSL_ERROR_WANT_WRITE;
    }

    /**
     * Empties the calling thread's OpenSSL error queue, so an entry this session's failure left
     * cannot surface as {@code SSL_ERROR_SSL} on the next session this thread drives.
     */
    private void clearErrorQueue() {
        handles.errorQueue().invokeClearError();
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
