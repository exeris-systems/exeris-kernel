/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.flow;

import eu.exeris.kernel.spi.exceptions.flow.FlowProviderException;
import eu.exeris.kernel.spi.flow.FlowEngine;
import eu.exeris.kernel.spi.flow.FlowEngineConfig;
import eu.exeris.kernel.spi.flow.FlowProvider;

import java.util.Comparator;
import java.util.ServiceLoader;

/**
 * Core: ServiceLoader-driven bootstrap for the Flow subsystem.
 *
 * <p>Core selects the highest-priority {@link FlowProvider} available on the classpath,
 * creates the matching {@link FlowEngine}, and returns the selected provider plus engine
 * for ScopedValue binding by the bootstrap layer.
 */
public final class FlowBootstrap {

    private static final String ERROR_NO_PROVIDER =
            "No FlowProvider found on classpath. "
            + "Add exeris-kernel-community or exeris-kernel-enterprise to your dependencies.";

    public record BootstrapResult(FlowProvider provider, FlowEngine engine) {
    }

    private FlowBootstrap() {
    }

    public static FlowEngine load(FlowEngineConfig config) {
        return loadWithProvider(config).engine();
    }

    public static BootstrapResult loadWithProvider(FlowEngineConfig config) {
        FlowProvider provider = ServiceLoader.load(FlowProvider.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .max(Comparator.comparingInt(FlowProvider::priority))
                .orElseThrow(() -> FlowProviderException.creationFailure(
                        "FlowBootstrap", ERROR_NO_PROVIDER, null));

        FlowEngine engine = provider.createEngine(config);

        FlowBootstrapSelectedEvent.emit(
                provider.getClass().getName(),
                provider.priority(),
                provider.providerId(),
                config.engineName()
        );

        return new BootstrapResult(provider, engine);
    }
}