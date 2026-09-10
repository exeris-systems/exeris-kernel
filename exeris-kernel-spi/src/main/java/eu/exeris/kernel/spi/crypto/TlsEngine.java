/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.crypto;

import eu.exeris.kernel.spi.exceptions.crypto.TlsException;
import eu.exeris.kernel.spi.exceptions.crypto.TlsHandshakeException;
import eu.exeris.kernel.spi.memory.LoanedBuffer;

/**
 * SPI: A single TLS session (handshake + record-layer I/O).
 *
 * <h2>Lifecycle</h2>
 * <pre>
 *  engine.notifyBound()         → transport/BIO binding completed
 *  engine.beginHandshake(out) → TlsStatus
 *  engine.unwrap(in, out)     → decrypt ciphertext → plaintext
 *  engine.wrap(in, out)       → encrypt plaintext  → ciphertext
 *  engine.close()             → send close_notify, release native TLS session handle
 * </pre>
 *
 * <p><b>Allocation:</b> zero-alloc on hot path — {@link #wrap} and {@link #unwrap} exchange
 * bytes between the supplied {@link LoanedBuffer} instances without allocating per record;
 * the handshake and the failure paths may allocate.
 * <p><b>Thread confinement:</b> owner thread — an engine is not thread-safe and is confined to
 * the carrier or virtual thread that drives its I/O.
 * <p><b>Ownership:</b> the caller owns every {@link LoanedBuffer} it passes in and releases it;
 * the engine borrows those buffers for the duration of the call and never retains them. The
 * engine owns its native TLS session handle and frees it in {@link #close()}.
 *
 * @implSpec Plaintext and ciphertext must reside in {@link LoanedBuffer} instances backed by
 *           off-heap {@code MemorySegment}: an implementation must not copy record payloads onto
 *           the Java heap, and works purely through segment views of the buffers it is handed.
 *           Failures on the decrypt path must be reported as
 *           {@link eu.exeris.kernel.spi.exceptions.crypto.TlsDecryptException}
 *           ({@code EX-NET-2003}) and failures on the handshake and encrypt paths as
 *           {@link TlsHandshakeException} ({@code EX-NET-2001}), so that a Glass-Box decoder
 *           can tell the two directions apart without parsing a message string.
 * @apiNote  The role of the {@code outbound} and {@code ciphertext} parameters depends on the
 *           I/O ownership mode the provider implementation configures:
 *           <ul>
 *             <li><b>Socket-owner mode:</b> the engine reads and writes the underlying transport
 *                 channel itself. Output parameters are not populated on return and the caller
 *                 transmits nothing separately; the {@code ciphertext} input of {@link #unwrap}
 *                 is likewise not consumed.</li>
 *             <li><b>Buffer-owner mode:</b> the engine has no direct I/O channel. After each call
 *                 the transport layer drains outbound encrypted bytes from the engine's internal
 *                 write buffer into the output parameter ({@link #wrap}, {@link #beginHandshake},
 *                 {@link #initiateShutdown}), and pre-fills the engine's internal read buffer
 *                 from the input parameter before calling {@link #unwrap}. The engine itself has
 *                 zero knowledge of that drain/fill step.</li>
 *           </ul>
 * @since 0.5
 * @see KernelCryptoProvider
 * @see TlsStatus
 */
public interface TlsEngine extends AutoCloseable {

    /**
     * Signals that transport-specific BIO/channel binding has completed and the
     * TLS session may begin the handshake state transitions.
     *
     * @throws TlsHandshakeException ({@code EX-NET-2001}) if called in an invalid lifecycle
     *         phase — a second time, or on a session that is already closed
     * @implSpec The default implementation is a no-op, which is correct for an engine that needs
     *           no explicit bind signal. An engine with explicit pre-handshake binding (an
     *           fd-owner or memory-BIO pipeline) overrides this method and enforces its
     *           state-machine contract there.
     */
    default void notifyBound() {
        // no-op by default
    }

    /**
     * Initiates or advances the TLS handshake.
     *
     * <p>Server mode: awaits the {@code ClientHello} from the peer.
     * Client mode: generates the initial {@code ClientHello}.
     *
     * <p>In socket-owner mode, handshake bytes are transmitted directly by the engine
     * via the wired transport; {@code outbound} is always left empty ({@code size = 0})
     * on return. In buffer-owner mode, the calling tier drains outbound bytes from the
     * engine's write buffer into {@code outbound} after this method returns.
     *
     * @param outbound buffer for outbound handshake bytes; always empty in socket-owner mode
     * @return status after this call ({@code NEED_UNWRAP}, {@code NEED_WRAP}, {@code FINISHED})
     * @throws TlsHandshakeException ({@code EX-NET-2001}) if the handshake cannot be initiated —
     *         the session was never bound, is already closed, or has entered
     *         {@link TlsPhase#ERROR}
     */
    TlsStatus beginHandshake(LoanedBuffer outbound);

    /**
     * Decrypts inbound ciphertext into {@code plaintext} (zero-copy).
     *
     * <p>In socket-owner mode, the engine reads ciphertext directly from the transport
     * channel; the {@code ciphertext} parameter is not consumed and may be empty. In
     * buffer-owner mode, the calling tier pre-fills the engine's read buffer from
     * {@code ciphertext} before invoking this method.
     *
     * @param ciphertext inbound encrypted bytes; not consumed in socket-owner mode
     * @param plaintext  output buffer for decrypted application data
     * @return operation status ({@code OK}, {@code NEED_HANDSHAKE}, {@code CLOSED})
     * @throws eu.exeris.kernel.spi.exceptions.crypto.TlsDecryptException
     *         ({@code EX-NET-2003}) if the session is closed or the handshake has not completed
     */
    TlsStatus unwrap(LoanedBuffer ciphertext, LoanedBuffer plaintext);

    /**
     * Encrypts outbound {@code plaintext} into {@code ciphertext} (zero-copy).
     *
     * <p>In socket-owner mode, the engine pushes encrypted bytes directly into the
     * transport channel; the {@code ciphertext} parameter is not populated and remains
     * empty ({@code size = 0}) on return. In buffer-owner mode, the calling tier drains
     * outbound bytes from the engine's write buffer into {@code ciphertext} after this
     * method returns.
     *
     * @param plaintext  application data to encrypt
     * @param ciphertext output buffer for encrypted bytes; always empty in socket-owner mode
     * @return operation status ({@code OK}, {@code NEED_HANDSHAKE}, {@code CLOSED})
     * @throws TlsHandshakeException ({@code EX-NET-2001}) if the session is closed or the
     *         handshake has not completed
     */
    TlsStatus wrap(LoanedBuffer plaintext, LoanedBuffer ciphertext);

    /**
     * Indicates whether the handshake has completed and the session may carry application data.
     *
     * @return {@code true} once the handshake has completed, {@code false} while it is still
     *         pending
     */
    boolean isHandshakeComplete();

    /**
     * Reports the application protocol this session and its peer agreed on through ALPN.
     *
     * @return the negotiated ALPN protocol name (for example {@code "h3"} or {@code "h2"}), or
     *         {@code null} when the peer offered no ALPN extension, none was configured, or the
     *         handshake has not completed
     */
    String negotiatedProtocol();

    /**
     * Initiates a graceful TLS shutdown (sends {@code close_notify}).
     *
     * <p>In socket-owner mode, the shutdown alert is written directly to the transport
     * channel; {@code outbound} is always left empty ({@code size = 0}) on return.
     * In buffer-owner mode, the calling tier drains outbound bytes from the engine's
     * write buffer into {@code outbound} after this method returns, then transmits
     * the alert bytes.
     *
     * @param outbound buffer for the {@code close_notify} alert bytes; always empty in socket-owner mode
     * @throws TlsException ({@code EX-NET-2001}) if the shutdown alert cannot be generated
     */
    void initiateShutdown(LoanedBuffer outbound);

    /**
     * Releases the native TLS session handle and all associated off-heap resources.
     *
     * @implSpec Idempotent — a second and any further call returns without throwing and without
     *           releasing anything twice.
     * @apiNote  For a graceful close, drive {@link #initiateShutdown} to completion first and
     *           close afterwards; closing an active session tears the TLS state down without
     *           waiting for the peer.
     */
    @Override
    void close();
}

