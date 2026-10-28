/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.transport.TransportConfig;

/**
 * Resolves the per-stream outbound write bound from {@code transport.maxQueuedWriteBytes}.
 *
 * <p>The limit lives on {@link TransportConfig#maxQueuedWriteBytes()} because it is meaningful to
 * every transport tier; this key is how Community's bootstraps fill that component. It is shared by
 * every site that builds a {@link TransportConfig} — the transport subsystem and the HTTP and
 * WebSocket listeners' own carriers — so the key cannot end up honoured on one path and silently
 * ignored on another. Validation is deliberately not repeated here: an out-of-range value is
 * refused by {@code TransportConfig}, where the value is named.
 *
 * @since 0.13
 */
public final class CommunityQueuedWriteBoundResolver {

    /** Config key for the bound; see {@code docs/subsystems/config.md}. */
    public static final String KEY = "transport.maxQueuedWriteBytes";

    private CommunityQueuedWriteBoundResolver() {
    }

    /**
     * Returns the configured bound, or {@link TransportConfig#DEFAULT_MAX_QUEUED_WRITE_BYTES}
     * when the key is absent or no provider is bound.
     *
     * @param configProvider the resolved provider, or {@code null} when none is bound
     * @return the bound to carry on the transport configuration
     */
    public static int resolve(ConfigProvider configProvider) {
        if (configProvider == null) {
            return TransportConfig.DEFAULT_MAX_QUEUED_WRITE_BYTES;
        }
        return configProvider.getInt(KEY).orElse(TransportConfig.DEFAULT_MAX_QUEUED_WRITE_BYTES);
    }
}
