/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.events;

import eu.exeris.kernel.core.bootstrap.BootstrapProviderSelector;
import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.events.EventEngineConfig;
import eu.exeris.kernel.spi.events.EventProvider;
import eu.exeris.kernel.spi.exceptions.events.EventProviderException;

import java.util.Comparator;

/**
 * Core: ServiceLoader-driven bootstrap for the Events subsystem.
 *
 * <p>Core selects the highest-priority {@link EventProvider} available on the classpath,
 * creates the matching {@link EventEngine}, and returns the selected provider plus engine
 * for ScopedValue binding by the bootstrap layer.
 */
public final class EventBootstrap {

    private static final String ERROR_NO_PROVIDER =
            "No EventProvider found on classpath. "
            + "Add exeris-kernel-community or exeris-kernel-enterprise to your dependencies.";

    /**
     * The provider selected off the classpath together with the engine it created.
     *
     * @param provider the highest-priority {@link EventProvider} found on the classpath
     * @param engine   the {@link EventEngine} created by {@code provider}
     */
    public record BootstrapResult(EventProvider provider, EventEngine engine) {
    }

    private EventBootstrap() {
    }

    /**
     * Selects the highest-priority {@link EventProvider} on the classpath and returns the
     * {@link EventEngine} it creates.
     *
     * @param config the engine configuration passed to {@link EventProvider#createEngine}
     * @return the engine created by the selected provider
     * @throws EventProviderException ({@code EX-EVENT-6004}) if no {@link EventProvider} is
     *         found on the classpath, or if the selected provider fails to create its engine
     */
    public static EventEngine load(EventEngineConfig config) {
        return loadWithProvider(config).engine();
    }

    /**
     * Selects the highest-priority {@link EventProvider} on the classpath, creates its engine,
     * and returns both for ScopedValue binding by the bootstrap layer.
     *
     * @param config the engine configuration passed to {@link EventProvider#createEngine}
     * @return the selected provider paired with the engine it created
     * @throws EventProviderException ({@code EX-EVENT-6004}) if no {@link EventProvider} is
     *         found on the classpath, or if the selected provider fails to create its engine
     */
    public static BootstrapResult loadWithProvider(EventEngineConfig config) {
        EventProvider provider = BootstrapProviderSelector.loadHighestPriority(
                        EventProvider.class,
                        Comparator.comparingInt(EventProvider::priority))
                .orElseThrow(() -> EventProviderException.creationFailure(
                        "EventBootstrap", ERROR_NO_PROVIDER, null));

        EventEngine engine = provider.createEngine(config);

        EventBootstrapSelectedEvent.emit(
                provider.getClass().getName(),
                provider.priority(),
                provider.providerId(),
                config.engineName()
        );

        return new BootstrapResult(provider, engine);
    }
}