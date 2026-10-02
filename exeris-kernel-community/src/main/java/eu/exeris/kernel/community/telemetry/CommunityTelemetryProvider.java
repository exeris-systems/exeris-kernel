/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.telemetry;

import eu.exeris.kernel.spi.exceptions.telemetry.TelemetryBootstrapException;
import eu.exeris.kernel.spi.telemetry.TelemetryConfig;
import eu.exeris.kernel.spi.telemetry.TelemetryProvider;
import eu.exeris.kernel.spi.telemetry.TelemetrySink;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Community: {@link TelemetryProvider} that wires standard {@link JfrTelemetrySink},
 * fallback {@link Slf4jTelemetrySink}, plus optional {@link ConsoleSink} and {@link FileSink}.
 *
 * <h2>Open-Core Positioning</h2>
 * <p>Community sinks produce structured JFR events and structured fallback logging via SLF4J,
 * with optional human-readable Console/File diagnostics.
 * There is no binary off-heap mmap dump — that is Enterprise-only ({@code BinaryGlassBoxSink}).
 *
 * <h2>Discovery</h2>
 * <p>Registered via {@code META-INF/services/eu.exeris.kernel.spi.telemetry.TelemetryProvider}.
 * Returns {@link #priority()} = 0; Enterprise wins with 100.
 *
 * @since 0.5
 */
@SuppressWarnings("PMD.CloseResource") // sinks created/closed atomically; lifecycle delegated to caller
public final class CommunityTelemetryProvider implements TelemetryProvider {

    private static final String PROVIDER_NAME = "ExerisCommunity/TextTelemetry";

    /**
     * Instantiated reflectively by {@code ServiceLoader} through this module's
     * {@code META-INF/services} registration of {@link TelemetryProvider}; not meant to be
     * constructed directly.
     */
    public CommunityTelemetryProvider() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /**
     * Creates the Community sink set selected by {@code config}: the JFR sink when
     * {@link TelemetryConfig#jfrSinkEnabled()} is set (the SLF4J sink otherwise),
     * plus the console and file sinks when their respective config flags request them.
     *
     * @param config telemetry configuration selecting which sinks to activate
     * @return an immutable, non-empty list of the activated sinks, in creation order
     * @throws TelemetryBootstrapException (EX-BOOT-3001) if a sink constructor throws;
     *         any sink already created by this call is closed before the exception propagates
     */
    @Override
    public List<TelemetrySink> createSinks(TelemetryConfig config) {
        List<TelemetrySink> sinks = new ArrayList<>(4);
        try {
            if (config.jfrSinkEnabled()) {
                sinks.add(new JfrTelemetrySink());
            } else {
                sinks.add(new Slf4jTelemetrySink());
            }
            if (config.consoleSinkEnabled()) {
                sinks.add(new ConsoleSink());
            }
            if (config.fileSinkPath() != null && !config.fileSinkPath().isBlank()) {
                sinks.add(new FileSink(Path.of(config.fileSinkPath()), config.maxEventQueueDepth()));
            }
        } catch (RuntimeException e) { //NOPMD AvoidCatchingGenericException — must close partial sinks
            closeCreatedSinks(sinks, e);
            throw new TelemetryBootstrapException(PROVIDER_NAME, "Sink creation failed", e);
        }
        return List.copyOf(sinks);
    }

    private static void closeCreatedSinks(List<TelemetrySink> sinks, RuntimeException failure) {
        for (TelemetrySink sink : sinks) {
            try {
                sink.close();
            } catch (RuntimeException closeFailure) { //NOPMD AvoidCatchingGenericException — drain all sinks
                failure.addSuppressed(closeFailure);
            }
        }
    }

    /**
     * Returns {@code "ExerisCommunity/TextTelemetry"}, the identity this provider
     * reports in bootstrap diagnostics.
     */
    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    /**
     * Returns the fixed Community-tier priority, {@code 0}.
     *
     * @see TelemetryProvider#priority()
     */
    @Override
    public int priority() {
        return 0;
    }
}


