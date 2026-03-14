/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.crypto;

import eu.exeris.kernel.spi.exceptions.crypto.TlsException;
import eu.exeris.kernel.spi.exceptions.crypto.TlsHandshakeException;
import eu.exeris.kernel.spi.memory.LoanedBuffer;

/**
 * SPI: A single TLS session (handshake + record-layer I/O).
 *
 * <h2>Zero-Copy Contract</h2>
 * <p>Plaintext and ciphertext MUST reside in {@link LoanedBuffer} instances backed by
 * off-heap {@code MemorySegment}. No data is copied to the Java heap during I/O.
 * The engine operates purely via {@code MemorySegment.asSlice()} views.
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
 * <h2>Buffer Semantics — I/O Ownership Contract</h2>
 * <p>The role of the {@code outbound} and {@code ciphertext} buffer parameters depends
 * on the I/O ownership mode configured by the provider implementation:
 * <ul>
 *   <li><b>Socket-owner mode:</b> The engine reads and writes directly to the underlying
 *       transport channel. Outbound and ciphertext output parameters are not populated by
 *       the engine on return; the caller does not need to transmit them separately. The
 *       {@code ciphertext} input parameter to {@link #unwrap} is also not consumed.</li>
 *   <li><b>Buffer-owner mode:</b> The engine has no direct I/O channel. After each call
 *       the transport layer must drain outbound encrypted bytes from the engine's internal
 *       write buffer into the output parameter ({@link #wrap}, {@link #beginHandshake},
 *       {@link #initiateShutdown}), and must pre-fill the engine's internal read buffer
 *       from the input parameter before calling {@link #unwrap}. The engine itself has
 *       zero knowledge of this drain/fill step.</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>Instances are NOT thread-safe by design. Each carrier/virtual thread owns its own engine.
 * Shared state lives only in the provider's global TLS context, which is read-only after bootstrap.
 *
 * @since 0.5.0
 * @see KernelCryptoProvider
 * @see TlsStatus
 */
public interface TlsEngine extends AutoCloseable {

    /**
     * Signals that transport-specific BIO/channel binding has completed and the
     * TLS session may begin the handshake state transitions.
     *
     * <p>Default no-op for engines that do not require an explicit bind signal.
     * Engines with explicit pre-handshake binding (for example fd-owner or memory-BIO
     * pipelines) should override this method and enforce their state machine contract.
     *
     * @throws TlsHandshakeException if called in an invalid lifecycle phase
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
     * @throws TlsHandshakeException if handshake cannot be initiated
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
     */
    TlsStatus wrap(LoanedBuffer plaintext, LoanedBuffer ciphertext);

    /**
     * Returns {@code true} if the handshake has completed and the session is
     * ready for application-data exchange.
     */
    boolean isHandshakeComplete();

    /**
     * Returns the negotiated ALPN protocol string (e.g., {@code "h3"}, {@code "h2"}),
     * or {@code null} if no ALPN was negotiated.
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
     * @throws TlsException if the shutdown alert cannot be generated
     */
    void initiateShutdown(LoanedBuffer outbound);

    /**
     * Releases the native TLS session handle and all associated off-heap resources.
     * Idempotent — multiple calls are safe.
     */
    @Override
    void close();
}

