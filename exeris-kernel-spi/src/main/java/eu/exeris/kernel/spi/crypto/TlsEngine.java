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
     * Initiates the TLS handshake.
     *
     * <p>Server mode: awaits the {@code ClientHello}; {@code outbound} may remain empty.
     * Client mode: generates and writes the initial {@code ClientHello} into {@code outbound}.
     *
     * @param outbound buffer to write initial handshake bytes into
     * @return status after this call (NEED_UNWRAP, NEED_WRAP, FINISHED)
     * @throws TlsHandshakeException if handshake cannot be initiated
     */
    TlsStatus beginHandshake(LoanedBuffer outbound);

    /**
     * Decrypts inbound ciphertext into {@code plaintext} (zero-copy).
     *
     * @param ciphertext inbound encrypted bytes from transport (off-heap backed)
     * @param plaintext  output buffer for decrypted application data
     * @return operation status (OK, NEED_HANDSHAKE, CLOSED)
     */
    TlsStatus unwrap(LoanedBuffer ciphertext, LoanedBuffer plaintext);

    /**
     * Encrypts outbound {@code plaintext} into {@code ciphertext} (zero-copy).
     *
     * @param plaintext  application data to encrypt
     * @param ciphertext output buffer for encrypted bytes (passed to transport)
     * @return operation status (OK, NEED_HANDSHAKE, CLOSED)
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
     * The {@code outbound} buffer receives the alert bytes to be transmitted by the transport.
     *
     * @param outbound buffer to write the {@code close_notify} alert into
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

