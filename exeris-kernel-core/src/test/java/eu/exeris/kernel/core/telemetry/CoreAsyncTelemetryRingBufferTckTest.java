/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.telemetry;

import eu.exeris.kernel.spi.telemetry.KernelEvent;
import eu.exeris.kernel.spi.telemetry.TelemetrySink;
import eu.exeris.kernel.tck.contract.telemetry.AbstractTelemetryRingBufferTck;
import org.junit.jupiter.api.DisplayName;

import java.time.Duration;
import java.util.List;

/**
 * Validation gate (Sprint 7): {@link AsyncTelemetrySink} must satisfy the
 * ring-buffer throughput + flush latency contract under 100k events/s.
 *
 * <p>The async sink wraps a no-op downstream so the test isolates the wrapper's
 * own enqueue + drain machinery. Capacity is sized above {@code emissionCount()}
 * so the contract does not depend on consumer drain rate — operators sizing the
 * ring at runtime should pick a capacity proportional to expected burst depth.
 */
@DisplayName("Core: AsyncTelemetrySink — ring-buffer 100k events/s contract")
class CoreAsyncTelemetryRingBufferTckTest extends AbstractTelemetryRingBufferTck {

    @Override
    protected TelemetrySink createSink() {
        // Capacity above emissionCount() so the contract test exercises the wrapper
        // rather than its drop policy (the latter has its own dedicated unit test).
        return AsyncTelemetrySink.start(List.of(new NoOpSink()),
                emissionCount() * 2,
                Duration.ofSeconds(2L));
    }

    private static final class NoOpSink implements TelemetrySink {

        @Override
        public void emit(KernelEvent event) {
            // intentionally no-op; the contract under test is the wrapper's enqueue path
        }

        @Override
        public void increment(String name, long delta) {
            // not exercised
        }

        @Override
        public void gauge(String name, long value) {
            // not exercised
        }

        @Override
        public void latency(String name, long nanoseconds) {
            // not exercised
        }

        @Override
        public String sinkName() {
            return "NoOpSink";
        }

        @Override
        public void close() {
            // no-op
        }
    }
}
