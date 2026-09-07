/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.telemetry;

/**
 * Package-private JSON string-escape helper for {@link Slf4jTelemetrySink}.
 *
 * <p>The leaf of the JSON serialization split: owns {@link #escapeJson(String)} and
 * {@link #appendJsonString(StringBuilder, String)}, both consumed by
 * {@link Slf4jTelemetryJsonWriter} (top entry) and
 * {@link Slf4jTelemetryRawArgsWriter} (rawArgs walker).
 */
final class Slf4jTelemetryJsonEscaper {

    private static final char FIRST_PRINTABLE_ASCII = 0x20;

    private Slf4jTelemetryJsonEscaper() {
        // package-private static utility — never instantiated.
    }

    @SuppressWarnings("PMD.CyclomaticComplexity") // JSON escape table is spec-defined exhaustive case list.
    /* default */ static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (current < FIRST_PRINTABLE_ASCII) {
                        appendUnicodeEscape(escaped, current);
                    } else {
                        escaped.append(current);
                    }
                }
            }
        }
        return escaped.toString();
    }

    /* default */ static void appendJsonString(StringBuilder builder, String value) {
        builder.append('"').append(escapeJson(value)).append('"');
    }

    private static void appendUnicodeEscape(StringBuilder builder, char value) {
        builder.append("\\u00");
        int high = (value >> 4) & 0x0F;
        int low = value & 0x0F;
        builder.append((char) (high < 10 ? '0' + high : 'a' + high - 10))
               .append((char) (low < 10 ? '0' + low : 'a' + low - 10));
    }
}
