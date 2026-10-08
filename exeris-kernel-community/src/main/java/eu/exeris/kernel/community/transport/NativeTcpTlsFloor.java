/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.crypto.CommunityKernelCryptoProvider;
import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.crypto.CryptoProviderConfig;
import eu.exeris.kernel.spi.exceptions.crypto.CryptoBootstrapException;
import eu.exeris.kernel.spi.exceptions.transport.TransportException;

/**
 * The protocol floor a carrier's TLS contexts carry: {@code crypto.tls.minVersion} when the kernel
 * configuration names it, the SPI default otherwise. A value the Community provider does not serve
 * refuses the carrier before any native allocation and never falls back to the default.
 */
final class NativeTcpTlsFloor {

    /** The kernel configuration key naming the minimum TLS version. */
    /* default */ static final String MIN_VERSION_KEY = "crypto.tls.minVersion";

    private NativeTcpTlsFloor() {
    }

    /**
     * Refuses an unusable configured value.
     *
     * @throws TransportException ({@code EX-NET-4004}, cause {@code EX-NET-2002}) if the key names a
     *         version the Community provider does not serve
     */
    /* default */ static void require() {
        configured();
    }

    /**
     * {@code base} with the configured floor, or {@code base} itself when none is configured.
     *
     * @param base a configuration carrying the SPI default floor
     * @return the configuration the carrier's contexts are built from
     * @throws TransportException as {@link #require()}
     */
    /* default */ static CryptoProviderConfig apply(CryptoProviderConfig base) {
        String floor = configured();
        if (floor == null) {
            return base;
        }
        return new CryptoProviderConfig(base.protocol(), base.certChainPath(), base.privateKeyPath(),
                base.alpnProtocols(), base.sessionCacheSize(), base.jfrEnabled(), floor);
    }

    private static String configured() {
        if (!KernelProviders.CURRENT_CONFIG.isBound()) {
            return null;
        }
        ConfigProvider provider = KernelProviders.CURRENT_CONFIG.get();
        String value = provider.getString(MIN_VERSION_KEY).orElse(null);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return CommunityKernelCryptoProvider.requireSupportedMinimumTlsVersion(value.strip());
        } catch (CryptoBootstrapException cause) {
            throw TransportException.bootstrapFailure(NativeTcpTransportProvider.PROVIDER_NAME,
                    MIN_VERSION_KEY + " names a TLS version this provider does not serve", cause);
        }
    }
}
