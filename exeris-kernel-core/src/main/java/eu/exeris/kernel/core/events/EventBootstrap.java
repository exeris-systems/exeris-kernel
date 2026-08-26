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

    public record BootstrapResult(EventProvider provider, EventEngine engine) {
    }

    private EventBootstrap() {
    }

    public static EventEngine load(EventEngineConfig config) {
        return loadWithProvider(config).engine();
    }

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