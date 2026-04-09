/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.events;

import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.events.EventEngineConfig;
import eu.exeris.kernel.spi.events.EventProvider;
import eu.exeris.kernel.spi.exceptions.events.EventProviderException;

public final class CommunityEventProvider implements EventProvider {

    private static final String PROVIDER_ID = "community";
    private static final String PROVIDER_NAME = "ExerisCommunity/Events";

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public int priority() {
        return 0;
    }

    @Override
    public EventEngine createEngine(EventEngineConfig config) {
        if (config == null) {
            throw EventProviderException.creationFailure(
                    PROVIDER_NAME,
                    "config is null",
                    new NullPointerException("config"));
        }
        return new CommunityEventEngine(config);
    }
}
