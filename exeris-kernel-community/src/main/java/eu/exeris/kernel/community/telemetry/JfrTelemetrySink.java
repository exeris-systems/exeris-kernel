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

    /**
     * Built by {@code CommunityTelemetryProvider} as the standard JFR sink for the Community
     * runtime, and directly by tests that need the Community sink identity in isolation.
     */
    public JfrTelemetrySink() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    /** Delegates to the wrapped core sink's JFR event emission. */
    @Override
    public void emit(KernelEvent event) {
        delegate.emit(event);
    }

    /** Delegates to the wrapped core sink's counter emission. */
    @Override
    public void increment(String name, long delta) {
        delegate.increment(name, delta);
    }

    /** Delegates to the wrapped core sink's gauge emission. */
    @Override
    public void gauge(String name, long value) {
        delegate.gauge(name, value);
    }

    /** Delegates to the wrapped core sink's latency-sample emission. */
    @Override
    public void latency(String name, long nanoseconds) {
        delegate.latency(name, nanoseconds);
    }

    /**
     * Returns {@code "ExerisCommunity/JfrTelemetrySink"} — this wrapper's own
     * identity, independent of the delegate's.
     */
    @Override
    public String sinkName() {
        return "ExerisCommunity/JfrTelemetrySink";
    }

    /** Delegates to the wrapped core sink's {@code close()}. */
    @Override
    public void close() {
        delegate.close();
    }
}