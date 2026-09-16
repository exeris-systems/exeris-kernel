/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.flow;

import eu.exeris.kernel.core.bootstrap.BootstrapProviderSelector;
import eu.exeris.kernel.spi.exceptions.flow.FlowProviderException;
import eu.exeris.kernel.spi.flow.FlowEngine;
import eu.exeris.kernel.spi.flow.FlowEngineConfig;
import eu.exeris.kernel.spi.flow.FlowProvider;

import java.util.Comparator;

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

    /**
     * The provider a {@link #loadWithProvider} lookup selected, paired with the engine it created.
     *
     * @param provider the winning {@link FlowProvider}, chosen by highest {@link FlowProvider#priority()}
     * @param engine   the {@link FlowEngine} that {@code provider} created for the requested config
     */
    public record BootstrapResult(FlowProvider provider, FlowEngine engine) {
    }

    private FlowBootstrap() {
    }

    /**
     * Loads the {@link FlowEngine} created by the highest-priority {@link FlowProvider} found on
     * the classpath.
     *
     * @param config the engine configuration passed to the winning provider's
     *               {@link FlowProvider#createEngine}
     * @return the engine the winning provider created; never {@code null}
     * @throws FlowProviderException {@code EX-FLOW-7001} if no {@link FlowProvider} is found on the
     *         classpath
     */
    public static FlowEngine load(FlowEngineConfig config) {
        return loadWithProvider(config).engine();
    }

    /**
     * Selects the highest-priority {@link FlowProvider} on the classpath, has it create the engine,
     * and emits {@link FlowBootstrapSelectedEvent} recording which provider won.
     *
     * @param config the engine configuration passed to the winning provider's
     *               {@link FlowProvider#createEngine}
     * @return the selected provider paired with the engine it created; never {@code null}
     * @throws FlowProviderException {@code EX-FLOW-7001} if no {@link FlowProvider} is found on the
     *         classpath
     */
    public static BootstrapResult loadWithProvider(FlowEngineConfig config) {
        FlowProvider provider = BootstrapProviderSelector.loadHighestPriority(
                        FlowProvider.class,
                        Comparator.comparingInt(FlowProvider::priority))
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