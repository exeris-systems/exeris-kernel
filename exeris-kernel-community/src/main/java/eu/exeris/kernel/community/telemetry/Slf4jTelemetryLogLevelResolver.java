/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.telemetry;

import eu.exeris.kernel.spi.telemetry.EventLevel;

/**
 * Package-private log-level routing helper for {@link Slf4jTelemetrySink}.
 *
 * <p>Extracted from {@link Slf4jTelemetrySink} in v0.8 Sprint 1 (QA-012) to close
 * {@code PMD.GodClass} + {@code PMD.CyclomaticComplexity} suppressions. JSON line
 * construction is handled separately by {@link Slf4jTelemetryJsonWriter}.
 *
 * <p>Resolution combines two signals — the event's declared {@link EventLevel} and
 * the EX-prefix of its code — and takes the higher of the two so a low-priority
 * declared level cannot mask a critical code family (e.g., an {@code INFO}-level
 * event with code {@code EX-MEM-1003} still emits at {@code ERROR}).
 */
final class Slf4jTelemetryLogLevelResolver {

    private static final String[] ERROR_CODE_PREFIXES = {
        "EX-MEM-", "EX-BOOT-", "EX-SEC-", "EX-PERS-", "EX-CFG-", "EX-GRPH-", "EX-HTTP-"
    };

    private static final String[] WARN_CODE_PREFIXES = {
        "EX-NET-", "EX-RUN-", "EX-EVENT-", "EX-FLOW-"
    };

    private Slf4jTelemetryLogLevelResolver() {
        // package-private static utility — never instantiated.
    }

    /* default */ static LogLevel resolve(EventLevel eventLevel, String code) {
        LogLevel mappedByEvent = mapEventLevel(eventLevel);
        LogLevel mappedByCode = mapCodeLevel(code);
        return mappedByEvent.ordinal() >= mappedByCode.ordinal() ? mappedByEvent : mappedByCode;
    }

    private static LogLevel mapEventLevel(EventLevel eventLevel) {
        return switch (eventLevel) {
            case INFO -> LogLevel.INFO;
            case WARN -> LogLevel.WARN;
            case ERROR, FATAL -> LogLevel.ERROR;
        };
    }

    private static LogLevel mapCodeLevel(String code) {
        if (matchesAnyPrefix(code, ERROR_CODE_PREFIXES)) {
            return LogLevel.ERROR;
        }
        if (matchesAnyPrefix(code, WARN_CODE_PREFIXES)) {
            return LogLevel.WARN;
        }
        return LogLevel.INFO;
    }

    private static boolean matchesAnyPrefix(String code, String... prefixes) {
        for (String prefix : prefixes) {
            if (code.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /* default */ enum LogLevel {
        INFO,
        WARN,
        ERROR
    }
}
