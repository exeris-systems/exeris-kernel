/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.tck.contract.transport;

import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.transport.TransportEngine;
import eu.exeris.kernel.spi.transport.TransportStream;
import eu.exeris.kernel.tck.contract.AbstractSubsystemZeroAllocTck;

import java.lang.foreign.ValueLayout;

/**
 * TCK: JFR Zero-Allocation monitor for the Transport I/O hot path.
 *
 * <h2>Hot Path Under Test</h2>
 * <p>The transport write path: allocate a network buffer → write sentinel data →
 * queue write on a stream → close buffer. Enterprise tier must achieve zero
 * {@code eu.exeris.*} heap allocations. Community tier is bounded.
 *
 * @since 0.5.0
 */
public abstract class TransportZeroAllocTck extends AbstractSubsystemZeroAllocTck {

    /** Creates a started {@link TransportEngine} with loopback binding. */
    protected abstract TransportEngine createEngine();

    /** Creates a {@link MemoryAllocator} for buffer allocation. */
    protected abstract MemoryAllocator createAllocator();

    /** Creates a writable {@link TransportStream} connected to the engine. */
    protected abstract TransportStream createWritableStream();

    private TransportEngine engine;
    private MemoryAllocator allocator;
    private TransportStream stream;

    @Override protected String subsystemName()     { return "Transport"; }
    @Override protected String hotPathDescription() { return "allocate(MICRO) → write sentinel → queueWrite(buf)"; }
    @Override protected int warmupIterations()      { return 100; }
    @Override protected int hotPathIterations()     { return 1_000; }

    @Override
    protected void bootstrapSubsystem() {
        engine = createEngine();
        allocator = createAllocator();
        stream = createWritableStream();
    }

    @Override
    protected void runSingleIteration() {
        LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO);
        buf.segment().set(ValueLayout.JAVA_LONG, 0, 0xCAFEL);
        // queueWrite takes ownership — caller does NOT close
        stream.queueWrite(buf, Long.BYTES);
    }

    @Override
    protected void tearDownSubsystem() {
        stream.close();
        engine.close();
        allocator.close();
    }
}
