/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.spi.bootstrap.BootstrapPhase;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.crypto.KernelCryptoProvider;

import java.util.List;
import java.util.function.ToIntFunction;

@SuppressWarnings({"PMD.CloseResource", "PMD.AvoidCatchingGenericException"})
final class CommunityCryptoSubsystem extends AbstractSingleProviderSubsystem<KernelCryptoProvider> {

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
    protected Class<KernelCryptoProvider> contract() {
        return KernelCryptoProvider.class;
    }

    @Override
    protected ToIntFunction<KernelCryptoProvider> priority() {
        return KernelCryptoProvider::priority;
    }

    @Override
    protected ScopedValue<KernelCryptoProvider> slot() {
        return KernelProviders.CRYPTO_PROVIDER;
    }

    /** Crypto is the one that owns a native handle, so it closes on the way down. */
    @Override
    public void stop() {
        super.stop();
        if (provider() instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt(); // restore interrupt status
            } catch (Exception _) {
                // best effort close, ignore any exception
            }
        }
    }
}
