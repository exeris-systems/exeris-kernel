/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.core.telemetry;

import eu.exeris.kernel.spi.telemetry.TelemetrySink;
import eu.exeris.kernel.tck.contract.telemetry.TelemetryZeroAllocTck;
import org.junit.jupiter.api.DisplayName;

/**
 * Core JFR Zero-Allocation TCK: wires {@link JfrTelemetrySink} to
 * {@link TelemetryZeroAllocTck}.
 *
 * <h2>Tier contract</h2>
 * <p>{@link JfrTelemetrySink} is a <strong>Community</strong> sink. Per
 * {@code telemetry.md}: <em>"the single allocation here is acceptable —
 * exceptions are never on the hot-path"</em>. JFR {@code Event} instances are
 * not thread-safe and must not be pooled or shared across threads.
 *
 * <h2>What is measured</h2>
 * <p>100 000 iterations of {@code sink.emit(pre-built KernelEvent)} inside a
 * live JFR recording window. The budget is
 * {@link TelemetryZeroAllocTck#maxExerisAllocationsPerIteration()} = 2:
 * one for the {@code KernelLifecycleJfrEvent} instance, one for any JFR
 * framework-internal per-emission state.
 *
 * <h2>Zero-GC path</h2>
 * <p>The true zero-allocation sink is the Enterprise {@code BinaryBlackBoxSink},
 * which writes fixed-width crash-log structs to an off-heap ring buffer via
 * {@code MemorySegment}. That sink will carry {@code supportsZeroGcHotPath=true}.
 *
 * <h2>JFR output</h2>
 * <p>Produces {@code target/jfr-reports/CoreTelemetryZeroAllocTckTest-Telemetry-[ts].jfr}.
 *
 * @since 0.5.0
 */
@DisplayName("Community: JfrTelemetrySink Bounded-Allocation JFR TCK")
class CoreTelemetryZeroAllocTckTest extends TelemetryZeroAllocTck {

    @Override
    protected TelemetrySink createSink() {
        return new JfrTelemetrySink();
    }
}
