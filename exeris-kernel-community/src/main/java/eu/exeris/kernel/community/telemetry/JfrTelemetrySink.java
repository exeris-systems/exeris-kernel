/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
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
 * @since 0.5
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