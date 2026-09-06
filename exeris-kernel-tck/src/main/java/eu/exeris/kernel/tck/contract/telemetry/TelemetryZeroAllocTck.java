/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.telemetry;

import eu.exeris.kernel.spi.telemetry.KernelEvent;
import eu.exeris.kernel.spi.telemetry.TelemetrySink;
import eu.exeris.kernel.tck.contract.AbstractSubsystemZeroAllocTck;

/**
 * Verifies the {@code eu.exeris.*} heap-allocation budget of repeated
 * {@code sink.emit(KernelEvent.info(...))} calls.
 *
 * <h2>Hot Path Under Test</h2>
 * <p>As declared here, this class runs the bounded-allocation assertion inherited from
 * {@link AbstractSubsystemZeroAllocTck}: at most {@link #maxExerisAllocationsPerIteration()}
 * (lowered here to {@code 2}) {@code eu.exeris.*} heap allocations per iteration. It does not
 * itself override {@link AbstractSubsystemZeroAllocTck#supportsZeroGcHotPath()}, so it does not
 * assert a zero-allocation contract; a binding for a sink that must be zero-allocating —
 * Enterprise's off-heap ring-buffer sink — establishes that by overriding
 * {@code supportsZeroGcHotPath()} to {@code true} in its own subclass.
 *
 * @since 0.5
 */
public abstract class TelemetryZeroAllocTck extends AbstractSubsystemZeroAllocTck {

    /**
     * Creates the {@link TelemetrySink} under test.
     *
     * @return a newly created, open sink; created in {@link #bootstrapSubsystem()}, before the
     *         JFR allocation window opens, and closed in {@link #tearDownSubsystem()}
     */
    protected abstract TelemetrySink createSink();

    private TelemetrySink sink;
    private KernelEvent preBuiltEvent;

    /**
     * Creates the contract; subclasses supply the binding via {@link #createSink()}.
     */
    public TelemetryZeroAllocTck() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

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
