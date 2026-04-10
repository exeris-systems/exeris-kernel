/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.telemetry;

import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.telemetry.EventLevel;
import eu.exeris.kernel.spi.telemetry.KernelEvent;
import eu.exeris.kernel.spi.telemetry.TelemetrySink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Objects;

/**
 * Community fallback {@link TelemetrySink} that emits structured JSON through SLF4J.
 *
 * <h2>Routing model</h2>
 * <p>{@code JfrTelemetrySink} is the primary sink when JFR is enabled. This sink is
 * used as a fallback path for environments where JFR is disabled or unavailable.
 *
 * <h2>Structured payload</h2>
 * <p>Each event is emitted as a single JSON line. Canonical EX fields are also exported
 * via MDC for downstream log appenders and centralized collectors.
 *
 * <p><b>Metrics contract:</b> {@code increment}, {@code gauge}, and {@code latency} are
 * intentionally no-op. This sink is lifecycle-event-oriented; use JFR as the primary
 * path for metric signals.
 *
 * <h2>Allocation and MDC policy</h2>
 * <p>This sink is a <b>diagnostic/fallback path</b>. It intentionally allocates Strings,
 * calls {@code getMessage()}, and uses SLF4J MDC. These are not violations of the
 * no-allocation contract: that contract applies only to Core runtime hot-path sinks
 * (e.g., {@link eu.exeris.kernel.core.telemetry.JfrTelemetrySink}). MDC is populated
 * only at the outermost emit boundary (try/finally) and is an intrinsic edge concern
 * of SLF4J structured logging — not kernel context propagation.</p>
 *
 * @since 0.5.0
 */
@SuppressWarnings({
    "PMD.CyclomaticComplexity",
    "PMD.TooManyMethods",
    "PMD.GodClass",         // high WMC from JSON serialization; no clean decomposition without allocation
    "PMD.UseTryWithResources",
    "PMD.CloseResource",
    "PMD.AvoidDuplicateLiterals",
    "PMD.UseVarargs",
    "PMD.ImplicitFunctionalInterface"
})
public final class Slf4jTelemetrySink implements TelemetrySink {

    private static final String DEFAULT_CODE = "EX-UNK-0000";
    private static final String SINK_NAME = "ExerisCommunity/Slf4jTelemetrySink";
    private static final char FIRST_PRINTABLE_ASCII = 0x20;

    private final LogAdapter logger;
    private final MdcAdapter mdc;
    private volatile boolean closed;

    public Slf4jTelemetrySink() {
        this(new Slf4jLogAdapter(LoggerFactory.getLogger(Slf4jTelemetrySink.class)), new Slf4jMdcAdapter());
    }

    /* default */ Slf4jTelemetrySink(LogAdapter logger, MdcAdapter mdc) {
        this.logger = logger;
        this.mdc = mdc;
    }

    @Override
    public void emit(KernelEvent event) {
        if (closed) {
            return;
        }
        Objects.requireNonNull(event, "event");

        String code = sanitizeCode(event.code());
        String component = sanitizeNullable(event.component());
        LogLevel resolvedLevel = resolveLogLevel(event.level(), code);
        String resolvedLevelName = resolvedLevel.name();
        String timestamp = event.timestamp().toString();
        ExerisKernelException exception = event.exception();

        MdcScope mdcScope = pushMdc(code, resolvedLevelName, component, timestamp);
        try {
            String json = buildJsonLine(resolvedLevelName, code, component, timestamp, exception);
            switch (resolvedLevel) {
                case INFO -> logger.info(json, exception);
                case WARN -> logger.warn(json, exception);
                case ERROR -> logger.error(json, exception);
            }
        } finally {
            mdcScope.close();
        }
    }

    private MdcScope pushMdc(String code, String level, String component, String timestamp) {
        MdcScope codeScope = mdc.put("ex.code", code);
        MdcScope levelScope = mdc.put("ex.level", level);
        MdcScope componentScope = mdc.put("ex.component", component);
        MdcScope timestampScope = mdc.put("ex.timestamp", timestamp);
        return () -> {
            timestampScope.close();
            componentScope.close();
            levelScope.close();
            codeScope.close();
        };
    }

    /**
     * @apiNote This sink is lifecycle-event-oriented. Metric increment signals are intentionally
     *          not emitted. Use {@link eu.exeris.kernel.core.telemetry.JfrTelemetrySink} as the
     *          primary path when metric signals are required.
     */
    @Override
    public void increment(String name, long delta) {
        // Fallback sink is lifecycle-oriented. Metrics are captured by JFR primary path.
    }

    /**
     * @apiNote This sink is lifecycle-event-oriented. Metric gauge signals are intentionally
     *          not emitted. Use {@link eu.exeris.kernel.core.telemetry.JfrTelemetrySink} as the
     *          primary path when metric signals are required.
     */
    @Override
    public void gauge(String name, long value) {
        // Fallback sink is lifecycle-oriented. Metrics are captured by JFR primary path.
    }

    /**
     * @apiNote This sink is lifecycle-event-oriented. Metric latency signals are intentionally
     *          not emitted. Use {@link eu.exeris.kernel.core.telemetry.JfrTelemetrySink} as the
     *          primary path when metric signals are required.
     */
    @Override
    public void latency(String name, long nanoseconds) {
        // Fallback sink is lifecycle-oriented. Metrics are captured by JFR primary path.
    }

    @Override
    public String sinkName() {
        return SINK_NAME;
    }

    @Override
    public void close() {
        closed = true;
    }

    private static String buildJsonLine(
            String resolvedLevelName,
            String code,
            String component,
            String timestamp,
            ExerisKernelException exception) {
        String message = buildStructuredMessage(exception);
        String rawArgs = buildStructuredRawArgsJson(exception != null ? exception.rawArgs() : null);
        return "{"
                + "\"timestamp\":\"" + escapeJson(timestamp) + "\","
                + "\"level\":\"" + escapeJson(resolvedLevelName) + "\","
                + "\"code\":\"" + escapeJson(code) + "\","
                + "\"component\":\"" + escapeJson(component) + "\","
                + "\"message\":\"" + escapeJson(message) + "\","
                + "\"rawArgs\":" + rawArgs
                + "}";
    }

    private static String buildStructuredMessage(ExerisKernelException exception) {
        if (exception == null) {
            return "";
        }
        String message = exception.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }
        String fallback = exception.getClass().getSimpleName();
        return fallback != null ? fallback : "";
    }

    private static LogLevel resolveLogLevel(EventLevel eventLevel, String code) {
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
        if (code.startsWith("EX-MEM-") || code.startsWith("EX-BOOT-") || code.startsWith("EX-SEC-")
                || code.startsWith("EX-PERS-") || code.startsWith("EX-CFG-")
                || code.startsWith("EX-GRPH-") || code.startsWith("EX-HTTP-")) {
            return LogLevel.ERROR;
        }
        if (code.startsWith("EX-NET-") || code.startsWith("EX-RUN-")
                || code.startsWith("EX-EVENT-") || code.startsWith("EX-FLOW-")) {
            return LogLevel.WARN;
        }
        return LogLevel.INFO;
    }

    private static String buildStructuredRawArgsJson(Object[] rawArgs) {
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
            appendJsonString(builder, stringValue);
            return;
        }
        if (value instanceof Character characterValue) {
            appendJsonString(builder, characterValue.toString());
            return;
        }
        if (value instanceof Enum<?> enumValue) {
            appendJsonString(builder, enumValue.name());
            return;
        }
        if (value.getClass().isArray()) {
            appendStructuredArray(builder, value);
            return;
        }
        appendJsonString(builder, "[unsupported]");
    }

    private static void appendFiniteOrString(StringBuilder builder, float value, boolean isFinite) {
        if (isFinite) {
            builder.append(value);
        } else {
            appendJsonString(builder, Float.toString(value));
        }
    }

    private static void appendFiniteOrString(StringBuilder builder, double value, boolean isFinite) {
        if (isFinite) {
            builder.append(value);
        } else {
            appendJsonString(builder, Double.toString(value));
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

    private static void appendJsonString(StringBuilder builder, String value) {
        builder.append('"').append(escapeJson(value)).append('"');
    }

    private static String sanitizeCode(String code) {
        if (code == null || code.isBlank()) {
            return DEFAULT_CODE;
        }
        return code;
    }

    private static String sanitizeNullable(String value) {
        return value == null ? "" : value;
    }

    private static String escapeJson(String value) {
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

    private static void appendUnicodeEscape(StringBuilder builder, char value) {
        builder.append("\\u00");
        int high = (value >> 4) & 0x0F;
        int low = value & 0x0F;
        builder.append((char) (high < 10 ? '0' + high : 'a' + high - 10))
               .append((char) (low < 10 ? '0' + low : 'a' + low - 10));
    }

    /* default */ enum LogLevel {
        INFO,
        WARN,
        ERROR
    }

    /* default */ interface LogAdapter {
        void info(String message, Throwable throwable);

        void warn(String message, Throwable throwable);

        void error(String message, Throwable throwable);
    }

    /* default */ interface MdcAdapter {
        MdcScope put(String key, String value);
    }

    /* default */ interface MdcScope extends AutoCloseable {

        @Override
        void close();
    }

    private static final class Slf4jLogAdapter implements LogAdapter {

        private final Logger logger;

        private Slf4jLogAdapter(Logger logger) {
            this.logger = logger;
        }

        @Override
        public void info(String message, Throwable throwable) {
            if (throwable == null) {
                logger.info(message);
            } else {
                logger.info(message, throwable);
            }
        }

        @Override
        public void warn(String message, Throwable throwable) {
            if (throwable == null) {
                logger.warn(message);
            } else {
                logger.warn(message, throwable);
            }
        }

        @Override
        public void error(String message, Throwable throwable) {
            if (throwable == null) {
                logger.error(message);
            } else {
                logger.error(message, throwable);
            }
        }
    }

    private static final class Slf4jMdcAdapter implements MdcAdapter {

        @Override
        public MdcScope put(String key, String value) {
            MDC.MDCCloseable closeable = MDC.putCloseable(key, value);
            return closeable::close;
        }
    }
}