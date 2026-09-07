/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.transport.TransportConfig;

/**
 * Resolves the PAQS stream-admission ceiling from {@code transport.paqs.maxActiveStreams}.
 *
 * <p>Shared by both sites that build a {@link TransportConfig} — the transport subsystem and the
 * HTTP listener's own carrier — so the key cannot end up honoured on one path and silently ignored
 * on the other. Validation is deliberately not repeated here: an out-of-range value is refused by
 * {@code TransportConfig} and again by the admission controller, where the value is named.
 *
 * @since 0.12
 */
public final class CommunityAdmissionCeilingResolver {

    /** Config key for the ceiling; see {@code docs/subsystems/config.md}. */
    public static final String KEY = "transport.paqs.maxActiveStreams";

    private CommunityAdmissionCeilingResolver() {
    }

    /**
     * Returns the configured ceiling, or {@link TransportConfig#DEFAULT_MAX_ACTIVE_STREAMS}
     * when the key is absent or no provider is bound.
     *
     * @param configProvider the resolved provider, or {@code null} when none is bound
     * @return the ceiling to carry on the transport configuration
     */
    public static int resolve(ConfigProvider configProvider) {
        if (configProvider == null) {
            return TransportConfig.DEFAULT_MAX_ACTIVE_STREAMS;
        }
        return configProvider.getInt(KEY).orElse(TransportConfig.DEFAULT_MAX_ACTIVE_STREAMS);
    }
}
