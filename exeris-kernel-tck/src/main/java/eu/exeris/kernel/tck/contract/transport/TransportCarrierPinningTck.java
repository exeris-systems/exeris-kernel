/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.transport;

import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.transport.TransportEngine;
import eu.exeris.kernel.spi.transport.TransportStream;
import eu.exeris.kernel.tck.contract.AbstractSubsystemCarrierPinningTck;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;

import java.lang.foreign.ValueLayout;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * TCK: Carrier pinning verifier for the Transport I/O hot path.
 *
 * <h2>Hot Path Under Test</h2>
 * <p>The transport write path: allocate a network buffer → write sentinel data →
 * queue write on a stream. This path must never pin a carrier thread — all I/O
 * must be non-blocking and VT-safe (no {@code synchronized}, no blocking socket ops).
 *
 * <h2>Usage — Community</h2>
 * {@snippet lang="java" :
 * public class CommunityTransportCarrierPinningTest extends TransportCarrierPinningTck {
 *     @Override protected TransportEngine createEngine()         { return new CommunityTransportEngine(...); }
 *     @Override protected MemoryAllocator createAllocator()      { return new CommunityMemoryAllocator(...); }
 *     @Override protected TransportStream createWritableStream() { return engine().openStream(loopback); }
 * }
 * }
 *
 * <h2>Usage — Enterprise</h2>
 * {@snippet lang="java" :
 * public class EnterpriseTransportCarrierPinningTest extends TransportCarrierPinningTck {
 *     @Override protected TransportEngine createEngine()         { return new EnterpriseTransportEngine(...); }
 *     @Override protected MemoryAllocator createAllocator()      { return new EnterpriseMemoryAllocator(...); }
 *     @Override protected TransportStream createWritableStream() { return engine().openStream(loopback); }
 * }
 * }
 *
 * @since 0.5
 * @see AbstractSubsystemCarrierPinningTck
 * @see TransportZeroAllocTck
 */
@DisplayName("Transport carrier pinning TCK")
public abstract class TransportCarrierPinningTck extends AbstractSubsystemCarrierPinningTck {

    // =========================================================================
    // Template methods
    // =========================================================================

    /**
     * Creates and starts the {@link TransportEngine} under test (loopback binding).
     *
     * @return a running engine
     */
    protected abstract TransportEngine createEngine();

    /**
     * Creates the {@link MemoryAllocator} used to allocate network buffers.
     *
     * @return an allocator shared by every per-VT slot
     */
    protected abstract MemoryAllocator createAllocator();

    /**
     * Creates a writable {@link TransportStream} connected to the running engine.
     *
     * @return a stream open for writing, one per pre-allocated VT slot
     */
    protected abstract TransportStream createWritableStream();

    // =========================================================================
    // State
    // =========================================================================

    /**
     * Number of pre-allocated per-VT slots — set to exactly
     * {@code warmupIterations() + hotPathIterations()} inside {@link #bootstrapSubsystem()}.
     * Declared as a non-final instance field so that static analysis tools do not flag
     * {@code i < vtSlotCount} as an always-true comparison.
     */
    private int vtSlotCount = 0;

    private TransportEngine engine;
    private MemoryAllocator allocator;

    /**
     * Pre-allocated per-VT streams — each VT owns exactly one slot.
     */
    private TransportStream[] streams;

    /**
     * Pre-allocated per-VT network buffers — each VT owns exactly one slot.
     */
    private LoanedBuffer[] buffers;

    /**
     * Monotonic slot counter — each executing VT claims the next available index.
     */
    private final AtomicInteger vtIndex = new AtomicInteger(0);

    /**
     * Creates the contract; subclasses supply the engine, allocator and writable stream via
     * {@link #createEngine()}, {@link #createAllocator()} and {@link #createWritableStream()}.
     *
     * <p>{@code engine}, {@code allocator}, {@code streams} and {@code buffers} start unset and
     * {@code vtSlotCount} starts at zero — {@link #bootstrapSubsystem()} populates all five
     * before the hot-path loop runs.
     */
    public TransportCarrierPinningTck() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    // =========================================================================
    // AbstractSubsystemCarrierPinningTck bindings
    // =========================================================================

    @Override
    protected String subsystemName() {
        return "Transport";
    }

    /**
     * Returns the bootstrapped {@link TransportEngine} for use in {@link #createWritableStream()}.
     *
     * @return the engine created by {@link #createEngine()} for this run
     */
    protected final TransportEngine engine() {
        return engine;
    }

    @Override
    protected String hotPathDescription() {
        return "allocate(MICRO) → write sentinel → queueWrite(buf)";
    }

    /**
     * Returns the number of warm-up VT iterations (phase 1 — discarded).
     * Delegates to the base-class accessor so this value stays in sync
     * with the harness even if the constant changes.
     *
     * @return the warm-up virtual-thread count
     */
    protected int warmupIterations() {
        return warmupVtCount();
    }

    /**
     * Returns the number of steady-state VT iterations (phase 2 — measured).
     * Delegates to the base-class accessor so this value stays in sync.
     *
     * @return the steady-state virtual-thread count
     */
    protected int hotPathIterations() {
        return steadyVtCount();
    }

    @Override
    protected void bootstrapSubsystem() {
        engine = createEngine();
        allocator = createAllocator();
        vtSlotCount = warmupVtCount() + steadyVtCount();

        streams = new TransportStream[vtSlotCount];
        buffers = new LoanedBuffer[vtSlotCount];
        for (int i = 0; i < vtSlotCount; i++) {
            streams[i] = createWritableStream();
            LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO);
            buf.segment().set(ValueLayout.JAVA_LONG, 0, 0xCAFEL);
            buffers[i] = buf;
        }
        vtIndex.set(0);
    }

    @Override
    protected void runSingleIteration() {
        int idx = vtIndex.getAndIncrement();
        streams[idx].queueWrite(buffers[idx], Long.BYTES);
    }

    @Override
    protected void tearDownSubsystem() {
        int usedSlots = vtIndex.get();
        if (streams != null && buffers != null) {
            // The drain budget is configurable. The default of 5s suits local development boxes
            // (>= 4 cores), but constrained CI runners (e.g. 2 vCPU GitHub Actions) need a wider
            // window — under thread pressure the reactor key release that ends `hasPendingData()`
            // may itself queue behind the test thread for several hundred ms per spin-cycle.
            // Operators set the system property directly; the built-in default keeps the
            // contract assertion strict where it can be honoured.
            long drainSeconds = Long.getLong("exeris.tck.transport.drainTimeoutSeconds", 5L);
            for (int i = 0; i < streams.length; i++) {
                TransportStream s = streams[i];
                if (s == null) continue;
                if (i < usedSlots) {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(drainSeconds);
                    while (s.hasPendingData()) {
                        if (System.nanoTime() > deadline) {
                            Assertions.fail(
                                    "Timeout waiting for TransportStream[" + i + "] pending data "
                                            + "to drain during TCK teardown after " + drainSeconds
                                            + "s. Implementation must complete queued writes within "
                                            + "the configured budget (override via "
                                            + "-Dexeris.tck.transport.drainTimeoutSeconds=N).");
                        }
                        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
                    }
                } else {
                    LoanedBuffer buf = buffers[i];
                    if (buf != null) buf.close();
                }
                s.close();
            }
        }
        if (engine != null) engine.close();
        if (allocator != null) allocator.close();
    }
}
