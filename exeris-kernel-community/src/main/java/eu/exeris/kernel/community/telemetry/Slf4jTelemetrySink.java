/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.telemetry;

import eu.exeris.kernel.community.telemetry.Slf4jTelemetryLogLevelResolver.LogLevel;
import eu.exeris.kernel.spi.config.KernelProfile;
import eu.exeris.kernel.spi.exceptions.ExceptionDisclosure;
import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.telemetry.KernelEvent;
import eu.exeris.kernel.spi.telemetry.TelemetrySink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Objects;
import java.util.function.Supplier;

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
 * <h2>Decomposition (v0.8 Sprint 1 QA-012)</h2>
 * <p>JSON line construction is delegated to {@link Slf4jTelemetryJsonWriter}; log-level
 * routing is delegated to {@link Slf4jTelemetryLogLevelResolver}. This sink owns the
 * emit lifecycle (closed-flag guard, MDC scope, log adapter routing) and the
 * test-facing adapter/scope interfaces. The split closes {@code PMD.GodClass} +
 * {@code PMD.TooManyMethods} + {@code PMD.CyclomaticComplexity} suppressions
 * previously held on this class.
 *
 * @since 0.5.0
 */
@SuppressWarnings({
    "PMD.UseTryWithResources",   // MDC scope close order is explicit (LIFO) — try-with-resources cannot express.
    "PMD.CloseResource"          // MdcScope is an AutoCloseable lambda — PMD doesn't track lambda close semantics.
})
public final class Slf4jTelemetrySink implements TelemetrySink {

    private static final String DEFAULT_CODE = KernelErrorCodes.EX_UNK_0000;
    private static final String SINK_NAME = "ExerisCommunity/Slf4jTelemetrySink";

    private final LogAdapter logger;
    private final MdcAdapter mdc;
    private final Supplier<KernelProfile> profileResolver;
    private volatile boolean closed;

    public Slf4jTelemetrySink() {
        this(new Slf4jLogAdapter(LoggerFactory.getLogger(Slf4jTelemetrySink.class)),
                new Slf4jMdcAdapter(),
                ExceptionDisclosure::activeProfile);
    }

    /* default */ Slf4jTelemetrySink(LogAdapter logger, MdcAdapter mdc) {
        // Test-only default: pin DEV so existing serialization fixtures remain meaningful.
        // Production uses the no-arg constructor which resolves the profile from scope.
        this(logger, mdc, () -> KernelProfile.DEV);
    }

    /* default */ Slf4jTelemetrySink(LogAdapter logger, MdcAdapter mdc, Supplier<KernelProfile> profileResolver) {
        this.logger = logger;
        this.mdc = mdc;
        this.profileResolver = profileResolver;
    }

    @Override
    public void emit(KernelEvent event) {
        if (closed) {
            return;
        }
        Objects.requireNonNull(event, "event");

        String code = sanitizeCode(event.code());
        String component = sanitizeNullable(event.component());
        LogLevel resolvedLevel = Slf4jTelemetryLogLevelResolver.resolve(event.level(), code);
        String resolvedLevelName = resolvedLevel.name();
        String timestamp = event.timestamp().toString();
        ExerisKernelException exception = event.exception();
        KernelProfile profile = profileResolver.get();
        Throwable disclosedThrowable = (exception != null && ExceptionDisclosure.discloseStackTrace(profile))
                ? exception
                : null;

        MdcScope mdcScope = pushMdc(code, resolvedLevelName, component, timestamp);
        try {
            String json = Slf4jTelemetryJsonWriter.buildJsonLine(
                    resolvedLevelName, code, component, timestamp, exception, profile);
            switch (resolvedLevel) {
                case INFO -> logger.info(json, disclosedThrowable);
                case WARN -> logger.warn(json, disclosedThrowable);
                case ERROR -> logger.error(json, disclosedThrowable);
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

    private static String sanitizeCode(String code) {
        if (code == null || code.isBlank()) {
            return DEFAULT_CODE;
        }
        return code;
    }

    private static String sanitizeNullable(String value) {
        return value == null ? "" : value;
    }

    /* default */ interface LogAdapter {
        void info(String message, Throwable throwable);

        void warn(String message, Throwable throwable);

        void error(String message, Throwable throwable);
    }

    /* default */ @FunctionalInterface
    interface MdcAdapter {
        MdcScope put(String key, String value);
    }

    /* default */ @FunctionalInterface
    interface MdcScope extends AutoCloseable {

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
