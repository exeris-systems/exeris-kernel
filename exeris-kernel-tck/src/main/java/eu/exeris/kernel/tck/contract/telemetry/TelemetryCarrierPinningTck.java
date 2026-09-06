/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.telemetry;

import eu.exeris.kernel.spi.telemetry.KernelEvent;
import eu.exeris.kernel.spi.telemetry.TelemetrySink;
import eu.exeris.kernel.tck.contract.AbstractSubsystemCarrierPinningTck;
import org.junit.jupiter.api.DisplayName;

/**
 * TCK: Carrier pinning verifier for the Telemetry emit() hot path.
 *
 * <h2>Hot Path Under Test</h2>
 * <p>{@code sink.emit(pre-built KernelEvent)} — telemetry emission must never
 * pin a carrier thread (no synchronized ring-buffer writes).
 *
 * @since 0.5
 * @see AbstractSubsystemCarrierPinningTck
 * @see TelemetryZeroAllocTck
 */
@DisplayName("Telemetry carrier pinning TCK")
public abstract class TelemetryCarrierPinningTck extends AbstractSubsystemCarrierPinningTck {

    /**
     * Creates the {@link TelemetrySink} under test.
     *
     * @return a newly created, open sink; created in {@link #bootstrapSubsystem()}, before the
     *         JFR pinning window opens, and closed in {@link #tearDownSubsystem()}
     */
    protected abstract TelemetrySink createSink();

    private TelemetrySink sink;
    private KernelEvent preBuiltEvent;

    @Override
    protected String subsystemName() {
        return "Telemetry";
    }

    @Override
    protected String hotPathDescription() {
        return "TelemetrySink.emit(pre-built KernelEvent)";
    }

    @Override
    protected void bootstrapSubsystem() {
        sink = createSink();
        preBuiltEvent = KernelEvent.info("EX-TCK-PIN-001", "TelemetryCarrierPinningTck");
    }

    @Override
    protected void runSingleIteration() {
        sink.emit(preBuiltEvent);
    }

    @Override
    protected void tearDownSubsystem() {
        sink.close();
    }
}
