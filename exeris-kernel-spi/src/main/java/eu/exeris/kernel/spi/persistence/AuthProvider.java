/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.persistence;

import eu.exeris.kernel.spi.memory.LoanedBuffer;

/**
 * SPI: Pluggable authentication provider for database connections.
 *
 * <h2>Off-Heap Auth Contract</h2>
 * <p>All authentication operations (SCRAM-SHA-256, MD5, cleartext) MUST be performed
 * entirely off-heap using the provided {@link LoanedBuffer} scratch space.
 * No heap-resident byte arrays or Strings are created for password hashing
 * or SASL message exchange on the Enterprise tier.
 *
 * <h2>Tier Separation</h2>
 * <ul>
 *   <li><b>Community:</b> Delegates to JDK {@code javax.crypto} for SCRAM.
 *       Scratch buffer used for SASL message framing.</li>
 *   <li><b>Enterprise:</b> OpenSSL-backed off-heap HMAC/SHA-256 via FFM.
 *       Zero heap allocation throughout the handshake.</li>
 * </ul>
 *
 * <h2>SPI Compliance</h2>
 * <p>No references to {@code PostgresAuthenticator}, {@code CryptoArenaManager},
 * or {@code OpenSSL} FFM handles. Only pure contracts.
 *
 * @since 0.5.0
 * @see PersistenceEngine
 */
public interface AuthProvider extends AutoCloseable {

    /**
     * Processes an authentication challenge from the database server.
     *
     * <p>The implementation reads the challenge from {@code challenge}, computes the
     * response using the configured credentials, and writes the response bytes into
     * {@code response}. All computation occurs off-heap in Enterprise tier.
     *
     * @param challenge server-sent authentication challenge (off-heap)
     * @param response  buffer to write the authentication response into (off-heap)
     * @return result indicating status (CONTINUE, COMPLETE, FAILED)
     */
    AuthResult authenticate(LoanedBuffer challenge, LoanedBuffer response);

    /**
     * Returns {@code true} if SCRAM-SHA-256 authentication is supported.
     *
     * @return SCRAM support flag
     */
    boolean supportsScram256();

    /**
     * Returns {@code true} if MD5 authentication is supported.
     *
     * @return MD5 support flag
     */
    boolean supportsMd5();

    /**
     * Returns {@code true} if the authentication handshake is complete.
     *
     * @return completion state
     */
    boolean isComplete();

    /**
     * Releases native resources (e.g., OpenSSL HMAC contexts).
     * Idempotent.
     */
    @Override
    void close();

    /**
     * Authentication result from a single exchange round.
     *
     * <p>Valhalla-ready record — scalarizable by C2.
     *
     * @param status     outcome of this round
     * @param bytesWritten number of response bytes written to the response buffer
     */
    record AuthResult(AuthStatus status, int bytesWritten) {

        /** Authentication exchange statuses. */
        public enum AuthStatus {
            /** More rounds needed — send response and await next challenge. */
            CONTINUE,
            /** Authentication successful — no more exchanges needed. */
            COMPLETE,
            /** Authentication failed — connection must be closed. */
            FAILED
        }
    }
}


