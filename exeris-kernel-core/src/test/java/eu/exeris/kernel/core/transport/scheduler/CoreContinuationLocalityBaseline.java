/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.transport.scheduler;

import eu.exeris.kernel.core.memory.ResourceArbiter;
import eu.exeris.kernel.core.memory.ResourceArbiterTestHelper;
import eu.exeris.kernel.core.memory.WatermarkManager;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LeakDetectionMode;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryStats;
import eu.exeris.kernel.spi.transport.StreamHandler;
import eu.exeris.kernel.spi.transport.StreamPriority;
import eu.exeris.kernel.spi.transport.TransportConnection;
import eu.exeris.kernel.spi.transport.TransportStream;
import eu.exeris.kernel.tck.perf.AbstractExerisBenchmark;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.lang.foreign.MemorySegment;
import java.lang.reflect.Method;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * JMH benchmark comparing C1 (default VirtualThread spawning) vs C2 (custom inline execution)
 * for stream scheduling throughput equivalence.
 *
 * <h2>Benchmark Variants</h2>
 * <ul>
 *   <li><b>C1 (Default):</b> Uses {@code PaqsScheduler(5-param constructor)} with default
 *       {@link StreamExecutionBackend} that spawns one Virtual Thread per admitted stream.
 *       This is the baseline production behavior: stream scheduling → VT spawn → handler execution.</li>
 *   <li><b>C2 (Research):</b> Uses {@code PaqsScheduler(6-param constructor)} with a custom
 *       {@link StreamExecutionBackend} that executes stream tasks inline (same thread, no VT spawn).
 *       Intended for latency and allocation profiling to establish whether Virtual Thread spawning
 *       cost is the dominant factor or orthogonal to scheduling throughput.</li>
 *   <li><b>C4 (Research):</b> Uses {@code PaqsScheduler(6-param constructor)} with a custom
 *       {@link StreamExecutionBackend} that spawns one Virtual Thread per stream, but pinned
 *       to a dedicated per-benchmark {@code ForkJoinPool}. Tests whether VT carrier affinity
 *       to a fixed pool improves throughput vs the JVM default global scheduler.</li>
 * </ul>
 *
 * <h2>Measurement Goals</h2>
 * <p>This benchmark establishes that the C2 inline variant produces <b>zero regression</b>
 * in admission throughput compared to C1. If C2 shows identical or higher throughput,
 * it implies the PAQS admission logic itself (not the VT spawn cost) is the throughput
 * bottleneck, justifying further C3+ research variants.
 *
 * <h2>Setup Strategy</h2>
 * <p>Both variants use identical admission/shedding configuration at Trial scope:
 * <ul>
 *   <li>{@link AdmissionController} with memory pressure at NORMAL level (50% utilization)</li>
 *   <li>{@link StreamLoadShedder} for rejected streams</li>
 *   <li>Minimal handler that completes immediately (no-op body).</li>
 * </ul>
 *
 * @since 0.5.1
 */
@State(Scope.Thread)
public class CoreContinuationLocalityBaseline extends AbstractExerisBenchmark {

    private static final String ENGINE_NAME = "ContinuationLocalityBench";
    private static final long TOTAL_HEAP = 1_000_000L;
    private static final long NORMAL_ALLOCATED = (long) (TOTAL_HEAP * 0.50);
    private static final Method VT_SCHEDULER_METHOD = resolveVtSchedulerMethod();

    private PaqsScheduler schedulerC1DefaultVt;
    private PaqsScheduler schedulerC2InlineExec;
    private PaqsScheduler schedulerC4LocalityAware;
    private ForkJoinPool localityPool;
    private AtomicInteger streamIdCounter;
    private final AtomicInteger streamHandlerInvokeCount = new AtomicInteger();

    // =========================================================================
    // Setup & Teardown (Trial scope)
    // =========================================================================

    /**
     * Initializes both scheduler variants at Trial boundary.
     *
     * <p>Builds stub infrastructure (allocator, arbiter, admission controller, load shedder)
     * and instantiates C1 and C2 scheduler variants with a minimal stream handler.
     * Both variants are configured identically except for the execution backend.
     */
    @Setup(Level.Trial)
    public void setup() {
        streamIdCounter = new AtomicInteger(0);
        streamHandlerInvokeCount.set(0);

        // Shared infrastructure
        MemoryAllocator allocator = stubAllocator(NORMAL_ALLOCATED, TOTAL_HEAP);
        WatermarkManager watermarkManager = new WatermarkManager(allocator);
        watermarkManager.refresh();
        ResourceArbiter arbiter = ResourceArbiterTestHelper.expiredGraceArbiter(watermarkManager);

        AdmissionController admissionController = new AdmissionController(arbiter);
        StreamLoadShedder loadShedder = new StreamLoadShedder(ENGINE_NAME);

        // Minimal handler: just record invocation
        StreamHandler handler = (stream) -> {
            streamHandlerInvokeCount.incrementAndGet();
            stream.close();
        };

        // Priority extractor: always return NORMAL
        Function<TransportStream, StreamPriority> priorityExtractor = (stream) -> StreamPriority.NORMAL;

        // C1: Default VT-per-stream backend (5-param constructor)
        schedulerC1DefaultVt = new PaqsScheduler(
                admissionController,
                loadShedder,
                handler,
                priorityExtractor,
                ENGINE_NAME
        );

        // C2: Custom inline execution backend (6-param constructor)
        StreamExecutionBackend inlineBackend = (threadName, task) -> task.run();
        schedulerC2InlineExec = new PaqsScheduler(
                admissionController,
                loadShedder,
                handler,
                priorityExtractor,
                ENGINE_NAME,
                inlineBackend
        );

        // C4: Locality-aware backend - VT per stream but pinned to dedicated FJP carrier pool.
        // FJP used as VT carrier scheduler (not structured-concurrency replacement).
        localityPool = new ForkJoinPool(Runtime.getRuntime().availableProcessors());
        StreamExecutionBackend localityBackend =
                this::startLocalityPinnedVirtualThread;
        schedulerC4LocalityAware = new PaqsScheduler(
            admissionController,
            loadShedder,
            handler,
            priorityExtractor,
            ENGINE_NAME,
            localityBackend
        );
    }

    /**
     * Shuts down both scheduler variants at Trial boundary.
     *
     * <p>Waits for any outstanding streams to complete and releases resources.
     */
    @TearDown(Level.Trial)
    public void tearDown() {
        if (schedulerC1DefaultVt != null) {
            try {
                schedulerC1DefaultVt.close();
            } catch (Exception e) {
                // Suppress exception during teardown
            }
        }
        if (schedulerC2InlineExec != null) {
            try {
                schedulerC2InlineExec.close();
            } catch (Exception e) {
                // Suppress exception during teardown
            }
        }
        if (schedulerC4LocalityAware != null) {
            try {
                schedulerC4LocalityAware.close();
            } catch (Exception e) {
                // Suppress exception during teardown
            }
        }
        ForkJoinPool pool = localityPool;
        if (pool != null) {
            pool.shutdown();
            try {
                pool.awaitTermination(2L, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // =========================================================================
    // Benchmark Methods
    // =========================================================================

    /**
     * C1 Baseline: Default VirtualThread-per-stream backend.
     *
     * <p>Measures the throughput of {@link PaqsScheduler#schedule(TransportStream)}
     * with the standard production behavior: admission decision → VT spawn → handler execution.
     *
     * <p>This establishes the baseline throughput expectation for admission-only logic.
     *
     * @return scheduling throughput (schedules per second)
     */
    @Benchmark
    public int c1DefaultVirtualThreadBackend() {
        TransportStream stream = createStream();
        schedulerC1DefaultVt.schedule(stream);
        return streamIdCounter.get();
    }

    /**
     * C2 Research Variant: Custom inline execution backend.
     *
     * <p>Measures the throughput of {@link PaqsScheduler#schedule(TransportStream)}
     * with a custom execution backend that runs stream tasks inline (no VT spawn).
     *
     * <p>If C2 throughput equals C1, the VT spawn cost is negligible compared to
     * the admission/shedding decision. If C2 shows higher throughput, VT spawn overhead
     * is a limiting factor and justifies further research into C3+ variants
     * (e.g., fiber pools, batched VT creation, or locality-aware scheduling).
     *
     * @return scheduling throughput (schedules per second)
     */
    @Benchmark
    public int c2CustomInlineBackend() {
        TransportStream stream = createStream();
        schedulerC2InlineExec.schedule(stream);
        return streamIdCounter.get();
    }

    /**
     * C4 Research Variant: Locality-aware Virtual Thread backend.
     *
     * <p>Measures the throughput of {@link PaqsScheduler#schedule(TransportStream)}
     * with a custom execution backend that spawns one Virtual Thread per stream, but
     * pins it to a dedicated per-benchmark {@link ForkJoinPool} as the carrier scheduler.
     *
     * <p>Hypothesis: reducing cross-pool continuation migrations may improve L1/L2 cache
     * affinity when a carrier always unmounts/remounts continuations from the same thread group.
     *
     * @return scheduling throughput (schedules per second)
     */
    @Benchmark
    public int c4LocalityAwareVirtualThreadBackend() {
        TransportStream stream = createStream();
        schedulerC4LocalityAware.schedule(stream);
        return streamIdCounter.get();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Creates a minimal stub {@link TransportStream} for testing.
     *
     * <p>The stream implements only the minimal SPI contract; read/write operations
     * are not exercised during benchmark execution.
     *
     * @return a new stream stub; never {@code null}
     */
    private TransportStream createStream() {
        long streamId = streamIdCounter.incrementAndGet();
        return new TransportStream() {
            @Override
            public int read(MemorySegment target, int maxBytes) {
                return -1;
            }

            @Override
            public void write(MemorySegment source, int length) {
                // Benchmark fixture: write not exercised
            }

            @Override
            public void queueWrite(LoanedBuffer buffer, int length) {
                // Benchmark fixture: async write not exercised
            }

            @Override
            public long streamId() {
                return streamId;
            }

            @Override
            public boolean isBidirectional() {
                return true;
            }

            @Override
            public boolean isClientInitiated() {
                return true;
            }

            @Override
            public TransportConnection connection() {
                return null;
            }

            @Override
            public boolean hasPendingData() {
                return false;
            }

            @Override
            public void close() {
                // Benchmark fixture: close is idempotent
            }
        };
    }

    /**
     * Creates a stub {@link MemoryAllocator} with fixed utilization.
     *
     * <p>Reports fixed stats without allocating or managing actual off-heap memory.
     *
     * @param allocated current allocated bytes
     * @param total     total capacity bytes
     * @return a stub allocator; never {@code null}
     */
    private static MemoryAllocator stubAllocator(long allocated, long total) {
        return new MemoryAllocator() {
            @Override
            public MemoryStats stats() {
                return new MemoryStats(total, allocated, total - allocated,
                        0, 0, allocated, 0, 0, LeakDetectionMode.DISABLED);
            }

            @Override
            public LoanedBuffer allocate(AllocationHint h) {
                throw new UnsupportedOperationException();
            }

            @Override
            public LoanedBuffer allocateNetwork(int b) {
                throw new UnsupportedOperationException();
            }

            @Override
            public LoanedBuffer allocateCarrierSlab(int i) {
                throw new UnsupportedOperationException();
            }

            @Override
            public LoanedBuffer allocateInfrastructure(long s) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void close() {
                // Stub allocator: no resources to release
            }
        };
    }

    private static Method resolveVtSchedulerMethod() {
        for (Method method : Thread.Builder.OfVirtual.class.getMethods()) {
            if ("scheduler".equals(method.getName()) && method.getParameterCount() == 1) {
                return method;
            }
        }
        return null;
    }

    private void startLocalityPinnedVirtualThread(String threadName, Runnable task) {
        Thread.Builder.OfVirtual builder = Thread.ofVirtual().name(threadName);
        Method schedulerMethod = VT_SCHEDULER_METHOD;
        if (schedulerMethod != null) {
            try {
                Object configured = schedulerMethod.invoke(builder, localityPool);
                if (configured instanceof Thread.Builder.OfVirtual configuredBuilder) {
                    configuredBuilder.start(task);
                    return;
                }
            } catch (ReflectiveOperationException ignored) {
                // Fallback to default virtual-thread scheduler.
            }
        }
        builder.start(task);
    }
}
