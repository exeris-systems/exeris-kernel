/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.crypto.CryptoProviderConfig;
import eu.exeris.kernel.spi.crypto.KernelCryptoProvider;
import eu.exeris.kernel.spi.exceptions.transport.TransportException;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.transport.TransportConfig;
import eu.exeris.kernel.spi.transport.TransportEngine;
import eu.exeris.kernel.spi.transport.TransportProvider;

/**
 * Community transport provider backed by {@link NativeTcpCarrier}.
 *
 * <p>Implementation is intentionally protocol-blind at the SPI level and uses only
 * provider slots from {@link KernelProviders}.
 *
 * @since 0.5.0
 */
@SuppressWarnings("PMD.AvoidCatchingGenericException")
public final class NativeTcpTransportProvider implements TransportProvider {

    private static final String PROVIDER_ID = "community-transport";
    private static final String PROVIDER_NAME = "ExerisCommunity/NativeTcpCarrier";

    @Override
    public TransportEngine createEngine(TransportConfig config) {
        if (!KernelProviders.MEMORY_ALLOCATOR.isBound()) {
            throw TransportException.bootstrapFailure(
                    PROVIDER_NAME,
                    "KernelProviders.MEMORY_ALLOCATOR is not bound",
                    null);
        }

        MemoryAllocator allocator = KernelProviders.MEMORY_ALLOCATOR.get();
        KernelCryptoProvider cryptoProvider =
                KernelProviders.CRYPTO_PROVIDER.isBound() ? KernelProviders.CRYPTO_PROVIDER.get() : null;

        CryptoProviderConfig cryptoConfig = resolveCryptoConfig(config);
        try {
            return new NativeTcpCarrier(config, allocator, cryptoProvider, cryptoConfig, PROVIDER_ID);
        } catch (RuntimeException cause) {
            throw TransportException.bootstrapFailure(PROVIDER_NAME, "Failed to create NativeTcpCarrier", cause);
        }
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public int priority() {
        return 0;
    }

    private static CryptoProviderConfig resolveCryptoConfig(TransportConfig config) {
        if (config == null) {
            return null;
        }
        if (config.mode() == eu.exeris.kernel.spi.transport.TransportMode.CLIENT) {
            return CryptoProviderConfig.tcpClient();
        }
        if (config.certPath() == null || config.keyPath() == null) {
            return null;
        }
        return new CryptoProviderConfig(
                CryptoProviderConfig.Protocol.TCP_TLS,
                java.nio.file.Path.of(config.certPath()),
                java.nio.file.Path.of(config.keyPath()),
                java.util.List.of("h2", "http/1.1"),
                512,
                true,
                CryptoProviderConfig.TLS_1_3
        );
    }
}
