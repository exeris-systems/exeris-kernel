/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.spi.bootstrap.BootstrapPhase;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.crypto.KernelCryptoProvider;

import java.util.List;
import java.util.function.UnaryOperator;

@SuppressWarnings({"PMD.CloseResource", "PMD.AvoidCatchingGenericException"})
final class CommunityCryptoSubsystem extends AbstractCommunitySubsystem {

    private KernelCryptoProvider cryptoProvider;

    @Override
    public String name() {
        return "crypto";
    }

    @Override
    public List<String> dependsOn() {
        return List.of("memory");
    }

    @Override
    public BootstrapPhase phase() {
        return BootstrapPhase.SERVICES;
    }

    @Override
    public void initialize() {
        cryptoProvider = CommunityProviderDiscovery.highestPriority(
                KernelCryptoProvider.class, KernelCryptoProvider::priority);
    }

    @Override
    public void start() {
        markRunning(cryptoProvider != null);
    }

    @Override
    public void stop() {
        markRunning(false);
        if (cryptoProvider instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt(); // restore interrupt status
            } catch (Exception _) {
                // best effort close, ignore any exception
            }
        }
    }

    @Override
    public UnaryOperator<ScopedValue.Carrier> providerBindings() {
        if (cryptoProvider == null) {
            return defaultProviderBindings();
        }
        return CommunityCarrierBindings.operator(
                CommunityCarrierBindings.binding(KernelProviders.CRYPTO_PROVIDER, cryptoProvider)
        );
    }
}
