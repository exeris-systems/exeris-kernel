/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.transport;

final class TransportNonDisabledValidator {

    private static final int MIN_REACTOR_COUNT = 1;
    private static final int MIN_CONNECTIONS = 1;
    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65_535;

    private TransportNonDisabledValidator() {
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
        validateServerDualFields(mode, bindAddress, port);
        validateSharedFields(reactorCount, maxConnections, idleTimeoutMillis);
    }

    private static void validateServerDualFields(TransportMode mode, String bindAddress, int port) {
        if (mode != TransportMode.CLIENT && (bindAddress == null || bindAddress.isBlank())) {
            throw new IllegalArgumentException("bindAddress must not be null/blank for SERVER/DUAL mode");
        }
        validatePort(mode, port);
    }

    private static void validatePort(TransportMode mode, int port) {
        if (mode == TransportMode.CLIENT) {
            if (port != 0 && (port < MIN_PORT || port > MAX_PORT)) {
                throw new IllegalArgumentException(
                        "port must be 0 (not used) or 1–65535 for CLIENT mode, got: " + port);
            }
        } else {
            if (port < MIN_PORT || port > MAX_PORT) {
                throw new IllegalArgumentException(
                        "port must be 1–65535 for SERVER/DUAL mode, got: " + port);
            }
        }
    }

    private static void validateSharedFields(int reactorCount, int maxConnections, long idleTimeoutMillis) {
        if (reactorCount < MIN_REACTOR_COUNT) {
            throw new IllegalArgumentException(
                    "reactorCount must be >= " + MIN_REACTOR_COUNT + ", got: " + reactorCount);
        }
        if (maxConnections < MIN_CONNECTIONS) {
            throw new IllegalArgumentException(
                    "maxConnections must be >= " + MIN_CONNECTIONS + ", got: " + maxConnections);
        }
        if (idleTimeoutMillis < 0) {
            throw new IllegalArgumentException(
                    "idleTimeoutMillis must be >= 0 (0 = no timeout), got: " + idleTimeoutMillis);
        }
    }
}
