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
 *  engine.beginHandshake(out) → TlsStatus
 *  engine.unwrap(in, out)     → decrypt ciphertext → plaintext
 *  engine.wrap(in, out)       → encrypt plaintext  → ciphertext
 *  engine.close()             → send close_notify, release native TLS session handle
 * </pre>
 *
 * <h2>Buffer Semantics — BIO Mode Contract</h2>
 * <p>The role of the {@code outbound} and {@code ciphertext} buffer parameters depends
 * on the BIO wired by the caller tier before the first handshake step:
 * <ul>
 *   <li><b>fd-owner BIO (Community tier — {@code SSL_set_fd}):</b> OpenSSL communicates
 *       directly with the kernel socket buffer. Outbound and ciphertext parameters are
 *       not populated by the engine on return; the transport does not need to transmit
 *       them. Inbound ciphertext is read directly from the socket, so the
 *       {@code ciphertext} parameter to {@link #unwrap} is also not consumed.</li>
 *   <li><b>Memory-BIO (Enterprise tier — {@code BIO_new_pair} + {@code SSL_set_bio}):</b>
 *       The engine has no I/O channel of its own. After each call the Enterprise tier
 *       must drain the write-BIO into the output buffer ({@link #wrap},
 *       {@link #beginHandshake}, {@link #initiateShutdown}) and pre-fill the read-BIO
 *       from the input buffer ({@link #unwrap}) using tier-specific BIO handles.
 *       The engine itself has zero knowledge of this drain/fill step.</li>
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
     * Initiates or advances the TLS handshake.
     *
     * <p>Server mode: awaits the {@code ClientHello} from the peer.
     * Client mode: generates the initial {@code ClientHello}.
     *
     * <p>In fd-owner BIO mode (Community), handshake bytes are transmitted directly
     * by OpenSSL via the wired fd; {@code outbound} is always left empty ({@code size = 0})
     * on return. In Memory-BIO mode (Enterprise), the calling tier drains the
     * write-BIO into {@code outbound} after this method returns.
     *
     * @param outbound buffer for outbound handshake bytes; always empty in fd-owner BIO mode
     * @return status after this call ({@code NEED_UNWRAP}, {@code NEED_WRAP}, {@code FINISHED})
     * @throws TlsHandshakeException if handshake cannot be initiated
     */
    TlsStatus beginHandshake(LoanedBuffer outbound);

    /**
     * Decrypts inbound ciphertext into {@code plaintext} (zero-copy).
     *
     * <p>In fd-owner BIO mode (Community), {@code SSL_read} pulls ciphertext directly
     * from the kernel socket buffer; the {@code ciphertext} parameter is not consumed
     * and may be empty. In Memory-BIO mode (Enterprise), the calling tier pre-fills
     * the read-BIO from {@code ciphertext} before invoking this method.
     *
     * @param ciphertext inbound encrypted bytes; not consumed in fd-owner BIO mode
     * @param plaintext  output buffer for decrypted application data
     * @return operation status ({@code OK}, {@code NEED_HANDSHAKE}, {@code CLOSED})
     */
    TlsStatus unwrap(LoanedBuffer ciphertext, LoanedBuffer plaintext);

    /**
     * Encrypts outbound {@code plaintext} into {@code ciphertext} (zero-copy).
     *
     * <p>In fd-owner BIO mode (Community), {@code SSL_write} pushes ciphertext directly
     * into the kernel socket buffer; the {@code ciphertext} parameter is not populated
     * and remains empty ({@code size = 0}) on return. In Memory-BIO mode (Enterprise),
     * the calling tier drains the write-BIO into {@code ciphertext} after this method returns.
     *
     * @param plaintext  application data to encrypt
     * @param ciphertext output buffer for encrypted bytes; always empty in fd-owner BIO mode
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
     * <p>In fd-owner BIO mode (Community), {@code SSL_shutdown} writes the alert
     * directly to the kernel socket buffer; {@code outbound} is always left empty
     * ({@code size = 0}) on return. In Memory-BIO mode (Enterprise), the calling tier
     * drains the write-BIO into {@code outbound} after this method returns, then
     * transmits the alert bytes.
     *
     * @param outbound buffer for the {@code close_notify} alert bytes; always empty in fd-owner BIO mode
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

