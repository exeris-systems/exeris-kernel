/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.transport;

final class TransportReactorDefaults {

    private static final int MIN_REACTOR_COUNT = 1;

    private TransportReactorDefaults() {
    }

    /* default */
    static int computeDefaultReactorCount(String propertyKey) {
        int availableProcessors = Math.max(MIN_REACTOR_COUNT, Runtime.getRuntime().availableProcessors());
        int optionalCap = parseOptionalPositiveInt(System.getProperty(propertyKey));
        if (optionalCap <= 0) {
            return availableProcessors;
        }
        return Math.min(availableProcessors, optionalCap);
    }

    private static int parseOptionalPositiveInt(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return 0;
        }
        try {
            int parsed = Integer.parseInt(rawValue.strip());
            return parsed > 0 ? parsed : 0;
        } catch (NumberFormatException _) {
            return 0;
        }
    }
}
