/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.telemetry;

import eu.exeris.kernel.spi.config.KernelProfile;
import eu.exeris.kernel.spi.exceptions.ExceptionDisclosure;
import eu.exeris.kernel.spi.exceptions.ExerisKernelException;

/**
 * Package-private top-level JSON line builder for {@link Slf4jTelemetrySink}.
 *
 * <p>The top entry of the JSON serialization split: composes the canonical
 * six-field event line ({@code timestamp/level/code/component/message/rawArgs})
 * and delegates string escaping to {@link Slf4jTelemetryJsonEscaper} and rawArgs
 * walking to {@link Slf4jTelemetryRawArgsWriter}.
 *
 * <p>Disclosure-aware: when an {@link ExerisKernelException} is present the
 * profile-driven {@link ExceptionDisclosure} rules govern both the rendered
 * message and the rawArgs payload.</p>
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals") // JSON syntax tokens ("\":\"", "\",\"") repeat per spec.
final class Slf4jTelemetryJsonWriter {

    private Slf4jTelemetryJsonWriter() {
        // package-private static utility — never instantiated.
    }

    /* default */ static String buildJsonLine(
            String resolvedLevelName,
            String code,
            String component,
            String timestamp,
            ExerisKernelException exception,
            KernelProfile profile) {
        String message = buildStructuredMessage(exception, profile);
        Object[] disclosed = exception == null
                ? null
                : ExceptionDisclosure.discloseRawArgs(exception, profile);
        String rawArgs = Slf4jTelemetryRawArgsWriter.buildStructuredRawArgsJson(disclosed);
        return "{"
                + "\"timestamp\":\"" + Slf4jTelemetryJsonEscaper.escapeJson(timestamp) + "\","
                + "\"level\":\"" + Slf4jTelemetryJsonEscaper.escapeJson(resolvedLevelName) + "\","
                + "\"code\":\"" + Slf4jTelemetryJsonEscaper.escapeJson(code) + "\","
                + "\"component\":\"" + Slf4jTelemetryJsonEscaper.escapeJson(component) + "\","
                + "\"message\":\"" + Slf4jTelemetryJsonEscaper.escapeJson(message) + "\","
                + "\"rawArgs\":" + rawArgs
                + "}";
    }

    private static String buildStructuredMessage(ExerisKernelException exception, KernelProfile profile) {
        if (exception == null) {
            return "";
        }
        String disclosed = ExceptionDisclosure.discloseMessage(exception, profile);
        if (disclosed != null && !disclosed.isBlank()) {
            return disclosed;
        }
        String fallback = exception.getClass().getSimpleName();
        return fallback != null ? fallback : "";
    }
}
