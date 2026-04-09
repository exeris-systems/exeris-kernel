/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.flow;

import eu.exeris.kernel.core.flow.CoreFlowEngine;
import eu.exeris.kernel.spi.exceptions.flow.FlowProviderException;
import eu.exeris.kernel.spi.flow.FlowEngine;
import eu.exeris.kernel.spi.flow.FlowEngineCapabilities;
import eu.exeris.kernel.spi.flow.FlowEngineConfig;
import eu.exeris.kernel.spi.flow.FlowProvider;

/** Community Flow provider: thin binding over the shared core heap engine. */
public final class CommunityFlowProvider implements FlowProvider {

    private static final String PROVIDER_ID = "community";
    private static final String PROVIDER_NAME = "ExerisCommunity/HeapFlow";
    private static final FlowEngineCapabilities CAPABILITIES =
            FlowEngineCapabilities.COMMUNITY.withProvider(PROVIDER_ID);

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

    @Override
    public FlowEngine createEngine(FlowEngineConfig config) {
        if (config == null) {
            throw FlowProviderException.creationFailure(
                    PROVIDER_NAME, "ENGINE_CREATE_FAILED", new NullPointerException("config"));
        }

        return new CoreFlowEngine(config, CAPABILITIES);
    }
}