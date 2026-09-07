/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.telemetry;

import eu.exeris.kernel.spi.telemetry.KernelEvent;
import eu.exeris.kernel.spi.telemetry.TelemetrySink;
import eu.exeris.kernel.tck.contract.AbstractSubsystemZeroAllocTck;

/**
 * TCK: JFR Zero-Allocation monitor for the Telemetry emit() hot path.
 *
 * <h2>Hot Path Under Test</h2>
 * <p>Repeated {@code sink.emit(KernelEvent.info(...))} calls. Enterprise
 * binary sinks must achieve zero {@code eu.exeris.*} heap allocations.
 * Community sinks (ConsoleSink, FileSink) are bounded.
 *
 * @since 0.5
 */
public abstract class TelemetryZeroAllocTck extends AbstractSubsystemZeroAllocTck {

    /**
     * Creates the {@link TelemetrySink} under test.
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
    protected int hotPathIterations() {
        return 100_000;
    }

    @Override
    protected int maxExerisAllocationsPerIteration() {
        return 2;
    }

    @Override
    protected void bootstrapSubsystem() {
        sink = createSink();
        // Pre-build the event BEFORE recording — avoid measuring KernelEvent construction
        preBuiltEvent = KernelEvent.info("EX-TCK-JFR-001", "TelemetryZeroAllocTck");
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
