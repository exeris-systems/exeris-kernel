/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.telemetry;

import eu.exeris.kernel.spi.telemetry.KernelEvent;
import eu.exeris.kernel.spi.telemetry.TelemetrySink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import eu.exeris.kernel.tck.support.TckScope;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Verifies that a {@link TelemetrySink} does not throw under concurrent high-throughput emission,
 * and that {@code close()} returns within a fixed bound after a burst.
 *
 * <h2>Target contract (telemetry.md)</h2>
 * <blockquote>
 * "BinaryGlassBoxSink (Enterprise): ring-buffer does not overflow under 100k events/s;
 * flush latency &lt; 1 ms P99."
 * </blockquote>
 *
 * <p>This class exercises that load shape against any {@link TelemetrySink} — Enterprise's
 * off-heap ring buffer, and Community's JFR path, which is expected to tolerate the same call
 * volume without throwing.
 *
 * <h2>What each test actually establishes</h2>
 * <ol>
 *   <li><b>No exception under concurrent load</b> — {@link #emissionCount()} pre-built events are
 *       emitted from {@link #concurrentEmitters()} virtual threads fanned out through
 *       {@link eu.exeris.kernel.tck.support.TckScope}. The test asserts that {@code emit()} never
 *       throws and that every call the test made returned normally; it does not read the sink's own
 *       state afterward, so it does not establish that the ring buffer retained every event rather
 *       than dropping some under saturation — the target's own drop policy is not verified here.</li>
 *   <li><b>close() returns within a fixed bound after a burst</b> — a single {@code close()} call,
 *       taken after emitting a burst of up to 10 000 events, must return within 100&nbsp;ms. This is
 *       one sample against a bound chosen for CI stability, not a P99 measurement, so it does not
 *       verify the &lt;1&nbsp;ms P99 figure quoted above.</li>
 * </ol>
 *
 * @since 0.5
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
     *
     * @return a newly created, open sink; created before each test and closed by the fixture's
     *         teardown
     */
    protected abstract TelemetrySink createSink();

    /**
     * Number of events for the throughput test.
     * Default: 100_000.
     * Enterprise implementations may use a larger value (1_000_000).
     *
     * @return the total number of events the throughput test emits across all emitters
     */
    protected int emissionCount() {
        return 100_000;
    }

    /**
     * Number of concurrent VTs emitting events in the stress test.
     * Default: 16 (simulate typical thread pool width).
     *
     * @return the number of virtual threads that share {@link #emissionCount()} between them
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
            try (TckScope scope = TckScope.openFailFast()) {

                for (int t = 0; t < emitters; t++) {
                    // Distribute the remainder to the first thread so the test always
                    // emits exactly emissionCount() events regardless of divisibility.
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
                                "Enterprise BinaryGlassBoxSink: async flush via StructuredTaskScope background thread. " +
                                "Community JFR sink: JFR framework handles flush on recording stop.",
                        burstSize, flushMs)
                .isLessThanOrEqualTo(100L);
    }
}
