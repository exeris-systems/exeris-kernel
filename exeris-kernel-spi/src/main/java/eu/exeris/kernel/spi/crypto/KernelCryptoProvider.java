/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.crypto;

/**
 * SPI: Pluggable cryptographic engine for the Exeris Kernel.
 *
 * <h2>Single Entry Point</h2>
 * <p>One method: {@link #createTlsEngine(CryptoProviderConfig)}.
 * The {@link CryptoProviderConfig#protocol()} field selects the engine path:
 * <ul>
 *   <li>{@code Protocol.TCP_TLS} — standard TLS 1.3 over stream transport (Community + Enterprise)</li>
 *   <li>{@code Protocol.QUIC}    — datagram-based QUIC transport (Enterprise only; Community throws
 *       {@link eu.exeris.kernel.spi.exceptions.crypto.CryptoBootstrapException})</li>
 * </ul>
 *
 * <h2>The Wall (SPI Compliance)</h2>
 * <p>Zero knowledge of underlying native transports, TLS library internals, or foreign memory mechanisms.
 *
 * <h2>Discovery</h2>
 * <p>Loaded via {@link java.util.ServiceLoader}. Enterprise returns {@link #priority()} = 100,
 * Community returns 0. Higher value wins.
 *
 * @since 0.5.0
 */
public interface KernelCryptoProvider {

    /**
     * Creates and initialises a {@link TlsEngine} from the given configuration.
     *
     * <p>Protocol routing:
     * <ul>
     *   <li>{@code Protocol.TCP_TLS} — both tiers supported</li>
     *   <li>{@code Protocol.QUIC}    — Community MUST throw
     *       {@link eu.exeris.kernel.spi.exceptions.crypto.CryptoBootstrapException}</li>
     * </ul>
     *
     * @param config cryptographic configuration including {@link CryptoProviderConfig#protocol()}
     * @return fully initialised TLS engine ready for handshake
     * @throws eu.exeris.kernel.spi.exceptions.crypto.CryptoBootstrapException
     *         if engine cannot be initialised or protocol unsupported
     */
    TlsEngine createTlsEngine(CryptoProviderConfig config);

    /**
     * Returns {@code true} if this provider supports {@link CryptoProviderConfig.Protocol#QUIC}.
     * Community MUST return {@code false}.
     * The bootstrapper uses this flag to activate or skip QUIC transport subsystems.
     */
    boolean supportsQuic();

    /** Display name used in JFR events and diagnostics. */
    String providerName();

    /**
     * Selection priority when multiple providers are on the classpath.
     * Enterprise: 100. Community: 0.
     */
    default int priority() {
        return 0;
    }
}
