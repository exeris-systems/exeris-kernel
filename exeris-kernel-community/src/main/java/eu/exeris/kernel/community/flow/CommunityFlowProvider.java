/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.flow;

import eu.exeris.kernel.core.flow.CoreFlowEngine;
import eu.exeris.kernel.spi.exceptions.flow.FlowProviderException;
import eu.exeris.kernel.spi.flow.FlowEngine;
import eu.exeris.kernel.spi.flow.FlowEngineCapabilities;
import eu.exeris.kernel.spi.flow.FlowEngineConfig;
import eu.exeris.kernel.spi.flow.FlowProvider;

/**
 * Community {@link FlowProvider} that creates a {@link CoreFlowEngine} — the heap-backed flow
 * engine shared with future thin provider bindings.
 *
 * <p>Registers at {@link #priority()} {@code 0}.
 */
public final class CommunityFlowProvider implements FlowProvider {

    private static final String PROVIDER_ID = "community";
    private static final String PROVIDER_NAME = "ExerisCommunity/HeapFlow";
    private static final FlowEngineCapabilities CAPABILITIES =
            FlowEngineCapabilities.COMMUNITY.withProvider(PROVIDER_ID);

    /**
     * Constructs the provider that {@link java.util.ServiceLoader} instantiates to resolve the
     * Community {@link FlowProvider}, per this module's registration under
     * {@code META-INF/services/eu.exeris.kernel.spi.flow.FlowProvider}.
     */
    public CommunityFlowProvider() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /**
     * Returns this provider's identifier, {@code "community"}.
     */
    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    /**
     * Returns this provider's display name, {@code "ExerisCommunity/HeapFlow"}.
     */
    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    /**
     * Returns {@code 0}, this provider's fixed selection priority.
     */
    @Override
    public int priority() {
        return 0;
    }

    /**
     * Creates a {@link CoreFlowEngine} configured with the Community
     * {@link FlowEngineCapabilities}.
     *
     * @param config the flow engine configuration
     * @return a newly created, not yet started {@link CoreFlowEngine}
     * @throws FlowProviderException ({@code EX-FLOW-7001}) if {@code config} is {@code null}
     */
    @Override
    public FlowEngine createEngine(FlowEngineConfig config) {
        if (config == null) {
            throw FlowProviderException.creationFailure(
                    PROVIDER_NAME, "ENGINE_CREATE_FAILED", new NullPointerException("config"));
        }

        return new CoreFlowEngine(config, CAPABILITIES);
    }
}