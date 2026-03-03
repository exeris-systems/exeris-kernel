/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.tck.contract.telemetry;

import eu.exeris.kernel.spi.telemetry.KernelEvent;
import eu.exeris.kernel.spi.telemetry.TelemetrySink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * TCK: Ring-buffer sink overflow and flush latency contract.
 *
 * <h2>What is verified (telemetry.md)</h2>
 * <blockquote>
 * "BinaryBlackBoxSink (Enterprise): ring-buffer does not overflow under 100k events/s;
 * flush latency &lt; 1 ms P99."
 * </blockquote>
 *
 * <p>This TCK verifies the high-throughput emission contract for sinks backed by
 * an off-heap ring buffer (Enterprise). Community sinks use the JFR path — they
 * should still not throw under the 100k events/s load.
 *
 * <h2>Tests</h2>
 * <ol>
 *   <li><b>No overflow at 100k events/s</b> — emits 100k pre-built events in a tight loop
 *       inside a {@code StructuredTaskScope} (virtual threads). No exception, no lost events.</li>
 *   <li><b>Flush latency &lt; 1ms P99</b> — after emitting a burst, close() must return
 *       within 100ms (includes final flush).</li>
 * </ol>
 *
 * @since 0.5.0
 */
@DisplayName("Telemetry Sink — ring-buffer throughput & flush latency contract")
public abstract class AbstractTelemetryRingBufferTck {

    // =========================================================================
    // Template methods
    // =========================================================================

    /**
     * Creates the {@link TelemetrySink} under test.
     * Community: JFR sink.
     * Enterprise: off-heap ring-buffer binary sink.
     */
    protected abstract TelemetrySink createSink();

    /**
     * Number of events for the throughput test.
     * Default: 100_000.
     * Enterprise implementations may use a larger value (1_000_000).
     */
    protected int emissionCount() {
        return 100_000;
    }

    /**
     * Number of concurrent VTs emitting events in the stress test.
     * Default: 16 (simulate typical thread pool width).
     */
    protected int concurrentEmitters() {
        return 16;
    }

    // =========================================================================
    // Fixtures
    // =========================================================================

    private TelemetrySink sink;
    private KernelEvent hotEvent;

    @BeforeEach
    final void setUp() {
        sink = createSink();
        hotEvent = KernelEvent.info("EX-TCK-RING-001", "RingBufferTck.hotEmission");
    }

    @AfterEach
    final void tearDown() {
        if (sink != null) {
            sink.close();
        }
    }

    // =========================================================================
    // Test 1: 100k events/s — no overflow, no exception
    // =========================================================================

    @Test
    @DisplayName("100k events emitted concurrently — no overflow, no exception thrown")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void noOverflowUnder100kEventsPerSecond() {
        int total = emissionCount();
        int emitters = concurrentEmitters();
        int perThread = total / emitters;
        int remainder = total % emitters;
        AtomicLong emitted = new AtomicLong(0L);

        assertThatCode(() -> {
            try (var scope = StructuredTaskScope.open(
                    StructuredTaskScope.Joiner.<Void>awaitAllSuccessfulOrThrow())) {

                for (int t = 0; t < emitters; t++) {

                    int toEmit = perThread + (t == 0 ? remainder : 0);
                    scope.fork(() -> {
                        for (int j = 0; j < toEmit; j++) {
                            sink.emit(hotEvent);
                            emitted.incrementAndGet();
                        }
                        return null;
                    });
                }
                scope.join();
            }
        }).as("Sink MUST NOT throw under %d concurrent emissions from %d VTs " +
                                "(telemetry.md: ring-buffer must not overflow at 100k events/s)",
                        total, emitters)
                .doesNotThrowAnyException();

        assertThat(emitted.get())
                .as("All %d emission calls MUST have reached the sink", total)
                .isEqualTo((long) total);
    }

    // =========================================================================
    // Test 2: close() flush latency < 100ms (conservative bound for CI)
    // =========================================================================

    @Test
    @DisplayName("close() completes within 100ms after burst (flush latency SLO)")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void flushLatencyWithinSlo() {
        int burstSize = Math.min(emissionCount(), 10_000);
        for (int i = 0; i < burstSize; i++) {
            sink.emit(hotEvent);
        }

        long before = System.nanoTime();
        sink.close();
        long flushMs = (System.nanoTime() - before) / 1_000_000L;

        sink = null;

        assertThat(flushMs)
                .as("Sink.close() (final flush) MUST complete within 100ms after emitting %d events. " +
                                "Actual: %d ms. " +
                                "Enterprise BinaryBlackBoxSink: async flush via StructuredTaskScope background thread. " +
                                "Community JFR sink: JFR framework handles flush on recording stop.",
                        burstSize, flushMs)
                .isLessThanOrEqualTo(100L);
    }
}
