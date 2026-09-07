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
 * @implSpec A provider that does not serve {@link Protocol#QUIC} must reject a configuration
 *           carrying it by throwing
 *           {@link eu.exeris.kernel.spi.exceptions.crypto.CryptoBootstrapException}
 *           ({@code EX-NET-2002}) from
 *           {@link KernelCryptoProvider#createTlsEngine(CryptoProviderConfig)}.
 * @implNote The Community provider rejects a configuration that carries a certificate chain
 *           without a matching private key, or vice versa, by throwing
 *           {@link eu.exeris.kernel.spi.exceptions.crypto.CryptoBootstrapException} from
 *           {@link KernelCryptoProvider#createTlsEngine(CryptoProviderConfig)}.
 * @apiNote  The three factories — {@link #httpsServer}, {@link #http3Server} and
 *           {@link #tcpClient()} — cover the deployment shapes the kernel ships with; reach for
 *           the canonical constructor only to vary the session cache, the ALPN list or the
 *           minimum TLS version.
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

    /**
     * Normalises the configuration: {@code alpnProtocols} is defensively copied into an immutable
     * list, and a {@code null} or blank {@code minimumTlsVersion} is replaced by {@link #TLS_1_3},
     * so that no deployment silently negotiates below TLS 1.3.
     *
     * @throws NullPointerException     if {@code protocol} or {@code alpnProtocols} is
     *                                  {@code null}
     * @throws IllegalArgumentException if {@code sessionCacheSize} is negative
     */
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

    /**
     * Builds the configuration for an HTTP/3 server — QUIC, TLS 1.3, ALPN {@code h3}, a
     * 512-entry session cache and JFR handshake events enabled.
     *
     * @param cert path to the PEM certificate chain file presented to clients
     * @param key  path to the PEM private key file matching {@code cert}
     * @return a server configuration selecting {@link Protocol#QUIC}, which only a provider whose
     *         {@link KernelCryptoProvider#supportsQuic()} is {@code true} can serve
     */
    public static CryptoProviderConfig http3Server(Path cert, Path key) {
        return new CryptoProviderConfig(Protocol.QUIC, cert, key,
                List.of("h3"), 512, true, TLS_1_3);
    }

    /**
     * Builds the configuration for a standard HTTPS server — TLS 1.3 over TCP, ALPN
     * {@code h2} then {@code http/1.1}, a 512-entry session cache and JFR handshake events
     * enabled.
     *
     * @param cert path to the PEM certificate chain file presented to clients
     * @param key  path to the PEM private key file matching {@code cert}
     * @return a server configuration selecting {@link Protocol#TCP_TLS}, which every tier serves
     */
    public static CryptoProviderConfig httpsServer(Path cert, Path key) {
        return new CryptoProviderConfig(Protocol.TCP_TLS, cert, key,
                List.of("h2", "http/1.1"), 512, true, TLS_1_3);
    }

    /**
     * Builds the configuration for a client that presents no certificate of its own.
     *
     * @return a client configuration selecting {@link Protocol#TCP_TLS} with no certificate or
     *         private key, no ALPN offer, the session cache disabled and JFR events off
     */
    public static CryptoProviderConfig tcpClient() {
        return new CryptoProviderConfig(Protocol.TCP_TLS, null, null,
                List.of(), 0, false, TLS_1_3);
    }
}
