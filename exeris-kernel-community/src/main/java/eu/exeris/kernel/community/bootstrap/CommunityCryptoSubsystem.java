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
import eu.exeris.kernel.spi.bootstrap.Subsystem;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.crypto.KernelCryptoProvider;

import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;
import java.util.function.UnaryOperator;

@SuppressWarnings({"PMD.CloseResource", "PMD.AvoidCatchingGenericException"})
final class CommunityCryptoSubsystem implements Subsystem {

    private KernelCryptoProvider cryptoProvider;
    private boolean running;

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
        cryptoProvider = ServiceLoader.load(KernelCryptoProvider.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .max(Comparator.comparingInt(KernelCryptoProvider::priority))
                .orElse(null);
    }

    @Override
    public void start() {
        running = (cryptoProvider != null);
    }

    @Override
    public void stop() {
        running = false;
        if (cryptoProvider instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // best effort close
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public UnaryOperator<ScopedValue.Carrier> providerBindings() {
        if (cryptoProvider == null) {
            return Subsystem.super.providerBindings();
        }
        return carrier -> carrier.where(KernelProviders.CRYPTO_PROVIDER, cryptoProvider);
    }
}
