/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
 * There is no binary off-heap mmap dump — that is Enterprise-only ({@code BinaryBlackBoxSink}).
 *
 * <h2>Discovery</h2>
 * <p>Registered via {@code META-INF/services/eu.exeris.kernel.spi.telemetry.TelemetryProvider}.
 * Returns {@link #priority()} = 0; Enterprise wins with 100.
 *
 * @since 0.5.0
 */
@SuppressWarnings("unused") // Loaded by ServiceLoader via reflection — no direct call site
public final class CommunityTelemetryProvider implements TelemetryProvider {

    private static final String PROVIDER_NAME = "ExerisCommunity/TextTelemetry";

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
            if (sinks.isEmpty()) {
                sinks.add(new Slf4jTelemetrySink());
            }
        } catch (IllegalArgumentException e) {
            throw new TelemetryBootstrapException(PROVIDER_NAME, "Sink creation failed", e);
        }
        return List.copyOf(sinks);
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public int priority() {
        return 0; // Enterprise wins with 100
    }
}


