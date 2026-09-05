/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.crypto;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * SPI: Immutable configuration record consumed by {@link KernelCryptoProvider#createTlsEngine}.
 *
 * <h2>Protocol Selection</h2>
 * <p>The {@link Protocol} field drives which engine path is used:
 * <ul>
 *   <li>{@link Protocol#TCP_TLS} — standard TLS 1.3 over TCP (Community + Enterprise)</li>
 *   <li>{@link Protocol#QUIC}    — QUIC over UDP (Enterprise only)</li>
 * </ul>
 * Community implementations MUST throw
 * {@link eu.exeris.kernel.spi.exceptions.crypto.CryptoBootstrapException} when
 * {@code protocol == QUIC}.
 *
 * <h2>Valhalla Readiness</h2>
 * <p>Standard {@code record} — candidate for {@code value record} once JEP 401 is mainline.
 *
 * @param protocol           target transport protocol
 * @param certChainPath      path to PEM certificate chain file (may be null for client-only)
 * @param privateKeyPath     path to PEM private key file (may be null for client-only)
 * @param alpnProtocols      ordered ALPN protocol names (e.g., {@code ["h3", "h2"]})
 * @param sessionCacheSize   max cached TLS sessions (0 = disabled)
 * @param jfrEnabled         whether to emit JFR events on handshake lifecycle
 * @param minimumTlsVersion  minimum TLS version string (e.g., {@code "TLSv1.3"})
 *
 * @since 0.5
 */
public record CryptoProviderConfig(
        Protocol protocol,
        Path certChainPath,
        Path privateKeyPath,
        List<String> alpnProtocols,
        int sessionCacheSize,
        boolean jfrEnabled,
        String minimumTlsVersion
) {
    /** Minimum TLS version enforced across all Exeris deployments. */
    public static final String TLS_1_3 = "TLSv1.3";

    /**
     * Transport protocol selector — drives engine path in {@link KernelCryptoProvider}.
     */
    public enum Protocol {
        /** Standard TLS 1.3 over TCP. Supported by Community and Enterprise. */
        TCP_TLS,
        /**
         * QUIC over UDP — Enterprise only.
         * Community implementations MUST throw
         * {@link eu.exeris.kernel.spi.exceptions.crypto.CryptoBootstrapException}.
         */
        QUIC
    }

    public CryptoProviderConfig {
        Objects.requireNonNull(protocol, "protocol must not be null");
        Objects.requireNonNull(alpnProtocols, "alpnProtocols must not be null");
        alpnProtocols = List.copyOf(alpnProtocols);
        if (sessionCacheSize < 0) {
            throw new IllegalArgumentException("sessionCacheSize must be >= 0");
        }
        minimumTlsVersion = (minimumTlsVersion == null || minimumTlsVersion.isBlank())
                ? TLS_1_3 : minimumTlsVersion;
    }

    /** Factory for HTTP/3 servers — QUIC + TLS 1.3 + ALPN h3. */
    public static CryptoProviderConfig http3Server(Path cert, Path key) {
        return new CryptoProviderConfig(Protocol.QUIC, cert, key,
                List.of("h3"), 512, true, TLS_1_3);
    }

    /** Factory for standard HTTPS servers — TCP TLS 1.3 + ALPN h2, http/1.1. */
    public static CryptoProviderConfig httpsServer(Path cert, Path key) {
        return new CryptoProviderConfig(Protocol.TCP_TLS, cert, key,
                List.of("h2", "http/1.1"), 512, true, TLS_1_3);
    }

    /** Factory for plain TCP TLS client (no cert). */
    public static CryptoProviderConfig tcpClient() {
        return new CryptoProviderConfig(Protocol.TCP_TLS, null, null,
                List.of(), 0, false, TLS_1_3);
    }
}
