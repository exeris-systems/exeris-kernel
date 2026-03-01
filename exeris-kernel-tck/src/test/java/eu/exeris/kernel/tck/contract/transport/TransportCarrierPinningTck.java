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
import eu.exeris.kernel.tck.contract.AbstractSubsystemCarrierPinningTck;
import org.junit.jupiter.api.DisplayName;

import java.lang.foreign.ValueLayout;

/**
 * TCK: Carrier pinning verifier for the Transport I/O hot path.
 *
 * <h2>Hot Path Under Test</h2>
 * <p>The transport write path: allocate a network buffer → write sentinel data →
 * queue write on a stream. This path must never pin a carrier thread — all I/O
 * must be non-blocking and VT-safe (no {@code synchronized}, no blocking socket ops).
 *
 * <h2>Usage — Community</h2>
 * <pre>{@code
 * public class CommunityTransportCarrierPinningTest extends TransportCarrierPinningTck {
 *     \@Override protected TransportEngine createEngine()         { return new CommunityTransportEngine(...); }
 *     \@Override protected MemoryAllocator createAllocator()      { return new CommunityMemoryAllocator(...); }
 *     \@Override protected TransportStream createWritableStream() { return engine.openStream(loopback); }
 * }
 * }</pre>
 *
 * <h2>Usage — Enterprise</h2>
 * <pre>{@code
 * public class EnterpriseTransportCarrierPinningTest extends TransportCarrierPinningTck {
 *     \@Override protected TransportEngine createEngine()         { return new EnterpriseTransportEngine(...); }
 *     \@Override protected MemoryAllocator createAllocator()      { return new EnterpriseMemoryAllocator(...); }
 *     \@Override protected TransportStream createWritableStream() { return engine.openStream(loopback); }
 * }
 * }</pre>
 *
 * @since 0.5.0
 * @see AbstractSubsystemCarrierPinningTck
 * @see TransportZeroAllocTck
 */
@DisplayName("Transport carrier pinning TCK")
public abstract class TransportCarrierPinningTck extends AbstractSubsystemCarrierPinningTck {

    // =========================================================================
    // Template methods
    // =========================================================================

    /** Creates and starts the {@link TransportEngine} under test (loopback binding). */
    protected abstract TransportEngine createEngine();

    /** Creates the {@link MemoryAllocator} used to allocate network buffers. */
    protected abstract MemoryAllocator createAllocator();

    /** Creates a writable {@link TransportStream} connected to the running engine. */
    protected abstract TransportStream createWritableStream();

    // =========================================================================
    // State
    // =========================================================================

    private TransportEngine engine;
    private MemoryAllocator allocator;
    private TransportStream stream;

    // =========================================================================
    // AbstractSubsystemCarrierPinningTck bindings
    // =========================================================================

    @Override
    protected String subsystemName() { return "Transport"; }

    @Override
    protected String hotPathDescription() {
        return "allocate(MICRO) → write sentinel → queueWrite(buf)";
    }

    @Override
    protected void bootstrapSubsystem() {
        engine    = createEngine();
        allocator = createAllocator();
        stream    = createWritableStream();
    }

    @Override
    protected void runSingleIteration() {
        // allocate(MICRO) is slab-pool acquire — must NOT pin (no synchronized on pool path)
        // queueWrite takes ownership of buf — caller does NOT close
        LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO);
        buf.segment().set(ValueLayout.JAVA_LONG, 0, 0xCAFEL);
        stream.queueWrite(buf, Long.BYTES);
    }

    @Override
    protected void tearDownSubsystem() {
        if (stream    != null) stream.close();
        if (engine    != null) engine.close();
        if (allocator != null) allocator.close();
    }
}

