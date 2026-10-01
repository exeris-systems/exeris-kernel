/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.crypto.tls;

/**
 * The static {@code detail} fragments a client TLS refusal carries in
 * {@link eu.exeris.kernel.spi.exceptions.crypto.TlsHandshakeException} ({@code EX-NET-2001}), and
 * what its {@code rawArgs[0]} holds under each.
 *
 * <table>
 *   <caption>Detail → {@code rawArgs[0]}</caption>
 *   <tr><th>Detail</th><th>{@code rawArgs[0]}</th></tr>
 *   <tr><td>{@link #PEER_VERIFICATION_FAILED}</td><td>the {@code X509_V_*} code</td></tr>
 *   <tr><td>{@link #HANDSHAKE_FAILED}</td><td>the {@code SSL_get_error} code</td></tr>
 *   <tr><td>{@link #PEER_IDENTITY_REJECTED}</td><td>{@code -1}</td></tr>
 *   <tr><td>{@link #INVALID_PEER_NAME}</td><td>{@code -1}</td></tr>
 *   <tr><td>{@link #NO_PEER_IDENTITY}</td><td>{@code -1}</td></tr>
 *   <tr><td>{@link #NO_PEER_VERIFIER}</td><td>{@code -1}</td></tr>
 * </table>
 *
 * <p>Constants rather than literals at the throw sites, so a Glass-Box decoder and a test can key
 * on the same value the thrower uses.
 *
 * @since 0.12
 */
public final class TlsFailureDetail {

    /** The server's certificate failed verification; {@code rawArgs[0]} is the {@code X509_V_*} code. */
    public static final String PEER_VERIFICATION_FAILED = "peer certificate verification failed";

    /** A handshake step failed for another reason; {@code rawArgs[0]} is the {@code SSL_get_error} code. */
    public static final String HANDSHAKE_FAILED = "handshake failed";

    /** OpenSSL refused the expected identity, so nothing could be verified against it. */
    public static final String PEER_IDENTITY_REJECTED = "peer identity rejected";

    /** The authority's host is neither a DNS name nor an IP literal. */
    public static final String INVALID_PEER_NAME = "authority host is neither a DNS name nor an IP literal";

    /** A client engine was asked to handshake with no identity to verify the server against. */
    public static final String NO_PEER_IDENTITY = "client engine has no expected peer identity";

    /** The bound crypto provider cannot verify an outbound peer, so the connection is refused. */
    public static final String NO_PEER_VERIFIER = "bound crypto provider cannot verify an outbound peer";

    private TlsFailureDetail() {
    }
}
