/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
