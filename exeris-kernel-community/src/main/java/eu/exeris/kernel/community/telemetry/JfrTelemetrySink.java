/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.telemetry;

import eu.exeris.kernel.spi.telemetry.KernelEvent;
import eu.exeris.kernel.spi.telemetry.TelemetrySink;

/**
 * Community canonical JFR {@link TelemetrySink}.
 *
 * <p>Delegates event and metric emission to the standard core implementation while exposing
 * a stable Community sink identity for diagnostics and provider-level assertions.
 *
 * @since 0.5.0
 */
public final class JfrTelemetrySink implements TelemetrySink {

    private final TelemetrySink delegate = new eu.exeris.kernel.core.telemetry.JfrTelemetrySink();

    @Override
    public void emit(KernelEvent event) {
        delegate.emit(event);
    }

    @Override
    public void increment(String name, long delta) {
        delegate.increment(name, delta);
    }

    @Override
    public void gauge(String name, long value) {
        delegate.gauge(name, value);
    }

    @Override
    public void latency(String name, long nanoseconds) {
        delegate.latency(name, nanoseconds);
    }

    @Override
    public String sinkName() {
        return "ExerisCommunity/JfrTelemetrySink";
    }

    @Override
    public void close() {
        delegate.close();
    }
}