/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.telemetry;

/**
 * Package-private rawArgs JSON-array writer for {@link Slf4jTelemetrySink}.
 *
 * <p>Extracted from {@link Slf4jTelemetrySink} in v0.8 Sprint 1 (QA-012) as the
 * middle layer of the JSON serialization split. Walks the disclosed rawArgs
 * array and serializes each element to JSON; delegates string escaping to
 * {@link Slf4jTelemetryJsonEscaper}.
 *
 * <p>Supported element types: boxed primitives, {@code String}, {@code Character},
 * {@code Enum}, and arrays of any of the above (recursive). Unknown types are
 * serialized as the literal string {@code "[unsupported]"}.</p>
 */
@SuppressWarnings({
    "PMD.CyclomaticComplexity",   // boxed-primitive ladder is exhaustive and flat by design.
    "PMD.UseVarargs"              // Object[] is the disclosed rawArgs payload passed verbatim from the caller.
})
final class Slf4jTelemetryRawArgsWriter {

    private Slf4jTelemetryRawArgsWriter() {
        // package-private static utility — never instantiated.
    }

    /* default */ static String buildStructuredRawArgsJson(Object[] rawArgs) {
        if (rawArgs == null || rawArgs.length == 0) {
            return "[]";
        }
        StringBuilder builder = new StringBuilder(rawArgs.length * 16);
        builder.append('[');
        for (int index = 0; index < rawArgs.length; index++) {
            if (index > 0) {
                builder.append(',');
            }
            appendStructuredRawArg(builder, rawArgs[index]);
        }
        builder.append(']');
        return builder.toString();
    }

    private static void appendStructuredRawArg(StringBuilder builder, Object value) {
        if (value == null) {
            builder.append("null");
            return;
        }
        if (value instanceof Boolean || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            builder.append(value);
            return;
        }
        if (value instanceof Float floatValue) {
            appendFiniteOrString(builder, floatValue, Float.isFinite(floatValue));
            return;
        }
        if (value instanceof Double doubleValue) {
            appendFiniteOrString(builder, doubleValue, Double.isFinite(doubleValue));
            return;
        }
        if (value instanceof String stringValue) {
            Slf4jTelemetryJsonEscaper.appendJsonString(builder, stringValue);
            return;
        }
        if (value instanceof Character characterValue) {
            Slf4jTelemetryJsonEscaper.appendJsonString(builder, characterValue.toString());
            return;
        }
        if (value instanceof Enum<?> enumValue) {
            Slf4jTelemetryJsonEscaper.appendJsonString(builder, enumValue.name());
            return;
        }
        if (value.getClass().isArray()) {
            appendStructuredArray(builder, value);
            return;
        }
        Slf4jTelemetryJsonEscaper.appendJsonString(builder, "[unsupported]");
    }

    private static void appendFiniteOrString(StringBuilder builder, float value, boolean isFinite) {
        if (isFinite) {
            builder.append(value);
        } else {
            Slf4jTelemetryJsonEscaper.appendJsonString(builder, Float.toString(value));
        }
    }

    private static void appendFiniteOrString(StringBuilder builder, double value, boolean isFinite) {
        if (isFinite) {
            builder.append(value);
        } else {
            Slf4jTelemetryJsonEscaper.appendJsonString(builder, Double.toString(value));
        }
    }

    private static void appendStructuredArray(StringBuilder builder, Object array) {
        int length = java.lang.reflect.Array.getLength(array);
        builder.append('[');
        for (int index = 0; index < length; index++) {
            if (index > 0) {
                builder.append(',');
            }
            appendStructuredRawArg(builder, java.lang.reflect.Array.get(array, index));
        }
        builder.append(']');
    }
}
