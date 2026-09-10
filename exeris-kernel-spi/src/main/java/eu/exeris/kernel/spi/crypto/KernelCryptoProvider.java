/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.crypto;

/**
 * SPI: Pluggable cryptographic engine for the Exeris Kernel.
 *
 * <h2>Single Entry Point</h2>
 * <p>One method: {@link #createTlsEngine(CryptoProviderConfig)}.
 * The {@link CryptoProviderConfig#protocol()} field selects the engine path:
 * <ul>
 *   <li>{@code Protocol.TCP_TLS} — standard TLS 1.3 over stream transport</li>
 *   <li>{@code Protocol.QUIC}    — datagram-based QUIC transport, offered only by a provider
 *       whose {@link #supportsQuic()} returns {@code true}</li>
 * </ul>
 *
 * <h2>The Wall (SPI Compliance)</h2>
 * <p>Zero knowledge of underlying native transports, TLS library internals, or foreign memory mechanisms.
 *
 * <p><b>Allocation:</b> allocates ({@link #createTlsEngine} builds one native TLS session context
 * and its off-heap session slab per engine; a bootstrap-path cost, never a per-record one).
 * <p><b>Ownership:</b> the caller owns the returned {@link TlsEngine} and releases it through
 * {@link TlsEngine#close()}; the provider retains no reference to it.
 *
 * @implSpec A provider is discovered through {@link java.util.ServiceLoader} and therefore needs a
 *           public no-argument constructor and a {@code META-INF/services} registration. When
 *           several providers are on the class path the highest {@link #priority()} wins, and a
 *           provider must return a non-negative value from it. A provider whose
 *           {@link #supportsQuic()} is {@code false} must reject a
 *           {@link CryptoProviderConfig.Protocol#QUIC} configuration
 *           by throwing
 *           {@link eu.exeris.kernel.spi.exceptions.crypto.CryptoBootstrapException}
 *           ({@code EX-NET-2002}) rather than by silently downgrading to TCP.
 * @implNote The Community provider serves TLS 1.3 over TCP at priority {@code 0} and does not
 *           support QUIC; the Enterprise provider adds datagram-based QUIC and takes priority
 *           {@code 100} so that it wins resolution wherever both are present.
 * @since 0.5
 */
public interface KernelCryptoProvider {

    /**
     * Creates and initialises a {@link TlsEngine} from the given configuration.
     *
     * @param config cryptographic configuration including {@link CryptoProviderConfig#protocol()}
     * @return a TLS engine whose native session context is already allocated and which is ready
     *         to be bound and handed to {@link TlsEngine#beginHandshake}
     * @throws eu.exeris.kernel.spi.exceptions.crypto.CryptoBootstrapException
     *         ({@code EX-NET-2002}) if the engine cannot be initialised — a missing native
     *         cryptographic library, an unreadable or mismatched certificate and key pair, or a
     *         {@link CryptoProviderConfig.Protocol} this provider does not serve
     * @implSpec A configuration this provider cannot serve must fail here, at bootstrap, rather
     *           than at the first record: the returned engine is expected to be usable.
     * @implNote The Community and Enterprise providers take the off-heap session slab from the
     *           bound {@code MemoryAllocator}, so a breach of the off-heap budget surfaces as
     *           {@code MemoryExhaustedException} ({@code EX-MEM-1001}) before any native memory
     *           is touched.
     */
    TlsEngine createTlsEngine(CryptoProviderConfig config);

    /**
     * Reports whether this provider can serve {@link CryptoProviderConfig.Protocol#QUIC}, which
     * the bootstrapper reads to activate or skip the QUIC transport subsystems.
     *
     * @return {@code true} if a {@code QUIC} configuration is accepted by
     *         {@link #createTlsEngine}, {@code false} if it is rejected with
     *         {@link eu.exeris.kernel.spi.exceptions.crypto.CryptoBootstrapException}
     */
    boolean supportsQuic();

    /**
     * Identifies this provider in JFR events, diagnostics and bootstrap failure payloads.
     *
     * @return a stable, non-blank display name for this provider, for example
     *         {@code "ExerisCommunity/OpenSSL3-TCP"}
     */
    String providerName();

    /**
     * Ranks this provider against the others found on the class path.
     *
     * @return a non-negative selection weight; the provider with the highest value is bound, and
     *         the default {@code 0} leaves an alternative provider free to outrank it
     */
    default int priority() {
        return 0;
    }
}
