/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.transport;

final class TransportConfigSupport {

    private TransportConfigSupport() {
    }

    /* default */
    static int computeDefaultReactorCount(String propertyKey) {
        return TransportReactorDefaults.computeDefaultReactorCount(propertyKey);
    }

    /* default */
    static void validateNonDisabled(
            TransportMode mode,
            String bindAddress,
            int port,
            int reactorCount,
            int maxConnections,
            long idleTimeoutMillis,
            int maxActiveStreams
    ) {
        TransportNonDisabledValidator.validateNonDisabled(
                mode,
                bindAddress,
                port,
                reactorCount,
                maxConnections,
                idleTimeoutMillis,
                maxActiveStreams
        );
    }
}