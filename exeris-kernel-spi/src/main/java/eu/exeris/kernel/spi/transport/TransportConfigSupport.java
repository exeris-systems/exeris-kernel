/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
            long idleTimeoutMillis
    ) {
        TransportNonDisabledValidator.validateNonDisabled(
                mode,
                bindAddress,
                port,
                reactorCount,
                maxConnections,
                idleTimeoutMillis
        );
    }
}