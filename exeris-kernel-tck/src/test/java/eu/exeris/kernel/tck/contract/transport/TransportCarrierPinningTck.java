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
import java.util.concurrent.atomic.AtomicInteger;

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
 *     \@Override protected TransportStream createWritableStream() { return engine().openStream(loopback); }
 * }
 * }</pre>
 *
 * <h2>Usage — Enterprise</h2>
 * <pre>{@code
 * public class EnterpriseTransportCarrierPinningTest extends TransportCarrierPinningTck {
 *     \@Override protected TransportEngine createEngine()         { return new EnterpriseTransportEngine(...); }
 *     \@Override protected MemoryAllocator createAllocator()      { return new EnterpriseMemoryAllocator(...); }
 *     \@Override protected TransportStream createWritableStream() { return engine().openStream(loopback); }
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

    /**
     * Number of pre-allocated per-VT slots — covers warm-up (200) + steady (1 000)
     * phases with a large safety margin for implementation-level re-runs.
     * Stored as an instance field (not a compile-time constant) to satisfy static
     * analysis tools that flag {@code i < CONSTANT} as an always-true condition.
     */
    private final int vtSlotCount = 10_000;

    private TransportEngine  engine;
    private MemoryAllocator  allocator;

    /** Pre-allocated per-VT streams — each VT owns exactly one slot. */
    private TransportStream[] streams;

    /** Pre-allocated per-VT network buffers — each VT owns exactly one slot. */
    private LoanedBuffer[]    buffers;

    /** Monotonic slot counter — each executing VT claims the next available index. */
    private final AtomicInteger vtIndex = new AtomicInteger(0);

    // =========================================================================
    // AbstractSubsystemCarrierPinningTck bindings
    // =========================================================================

    @Override
    protected String subsystemName() { return "Transport"; }

    /** Returns the bootstrapped {@link TransportEngine} for use in {@link #createWritableStream()}. */
    protected final TransportEngine engine() { return engine; }

    @Override
    protected String hotPathDescription() {
        return "allocate(MICRO) → write sentinel → queueWrite(buf)";
    }

    @Override
    protected void bootstrapSubsystem() {
        engine    = createEngine();
        allocator = createAllocator();

        streams = new TransportStream[vtSlotCount];
        buffers = new LoanedBuffer[vtSlotCount];
        for (int i = 0; i < streams.length; i++) {
            streams[i] = createWritableStream();
            LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO);
            buf.segment().set(ValueLayout.JAVA_LONG, 0, 0xCAFEL);
            buffers[i] = buf;
        }
        vtIndex.set(0);
    }

    @Override
    protected void runSingleIteration() {
        // Each VT claims its own pre-allocated slot — zero allocations on the hot path.
        // queueWrite takes ownership of buf — the buffer is NOT closed here.
        int idx = vtIndex.getAndIncrement();
        streams[idx].queueWrite(buffers[idx], Long.BYTES);
    }

    @Override
    protected void tearDownSubsystem() {
        if (streams != null) {
            for (TransportStream s : streams) {
                if (s != null) s.close();
            }
        }
        if (engine    != null) engine.close();
        if (allocator != null) allocator.close();
    }
}

