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
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
    private static final boolean JFR_ENABLED = Boolean.parseBoolean(
            System.getProperty("exeris.benchmark.jfr.enabled", "true"));
    private static final String ARTIFACT_BASE = System.getProperty(
            "exeris.benchmark.artifacts.dir", "target/benchmark-artifacts");

    @Param({"16", "32", "64"})
    public int concurrency;

    private PaqsScheduler schedulerC1DefaultVt;
    private PaqsScheduler schedulerC2InlineExec;
    private PaqsScheduler schedulerC4LocalityAware;
    private ForkJoinPool localityPool;
    private AtomicInteger streamIdCounter;
    private final AtomicInteger streamHandlerInvokeCount = new AtomicInteger();
    private jdk.jfr.Recording jfrRecording;
    private Path artifactDir;
    private String jfrFilePath;

    // =========================================================================
    // Setup & Teardown (Trial scope)
    // =========================================================================

    /**
     * Preflight validation: verify scheduler backends are available.
     * Fail fast if any backend is unavailable.
     */
    private void preflight() {
        try {
            Class<?> schedulerClass = Class.forName(
                    "eu.exeris.kernel.core.transport.scheduler.PaqsScheduler");
            Class<?> backendClass = Class.forName(
                    "eu.exeris.kernel.core.transport.scheduler.StreamExecutionBackend");
            if (schedulerClass == null || backendClass == null) {
                throw new IllegalStateException(
                        "PaqsScheduler or StreamExecutionBackend SPI not available");
            }
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "Preflight validation failed: scheduler infrastructure unavailable", e);
        }
    }

    /**
     * Create per-trial artifact directory and write JSON manifest.
     * Manifest includes benchmark name, backend mode, concurrency, timestamp, JFR path.
     */
    private void initializeArtifacts() {
        try {
            artifactDir = Files.createDirectories(Paths.get(ARTIFACT_BASE)
                    .resolve("trial-" + System.currentTimeMillis()));
            
            String jfrFileName = JFR_ENABLED ? 
                    "core-locality-c" + concurrency + "-" + System.nanoTime() + ".jfr" : null;
            jfrFilePath = JFR_ENABLED ? artifactDir.resolve(jfrFileName).toString() : null;
            
            String manifestJson = buildManifest(jfrFileName);
            Files.write(artifactDir.resolve("manifest.json"),
                    manifestJson.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize benchmark artifacts", e);
        }
    }

    /**
     * Build JSON manifest with benchmark metadata.
     */
    private String buildManifest(String jfrFileName) {
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"benchmarkName\":\"CoreContinuationLocalityBaseline\",");
        json.append("\"concurrency\":").append(concurrency).append(",");
        json.append("\"timestamp\":").append(System.currentTimeMillis()).append(",");
        json.append("\"jfrEnabled\":").append(JFR_ENABLED).append(",");
        json.append("\"jfrFile\":").append(jfrFileName != null ? "\"" + jfrFileName + "\"" : "null").append(",");
        json.append("\"perfStatMode\":\"external-runner\",");
        json.append("\"perfStatIntegrated\":false");
        json.append("}");
        return json.toString();
    }

    /**
     * Initializes each scheduler variant with isolated infrastructure.
     * Each variant (C1, C2, C4) has isolated AdmissionController, StreamLoadShedder.
     */
    @Setup(Level.Trial)
    public void setup() {
        preflight();
        initializeArtifacts();
        
        if (JFR_ENABLED) {
            try {
                jfrRecording = new jdk.jfr.Recording();
                jfrRecording.setDestination(Paths.get(jfrFilePath));
                jfrRecording.start();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to start JFR recording", e);
            }
        }
        
        streamIdCounter = new AtomicInteger(0);
        streamHandlerInvokeCount.set(0);

        // C1: Isolated infrastructure with default VT backend
        {
            MemoryAllocator allocatorC1 = stubAllocator(NORMAL_ALLOCATED, TOTAL_HEAP);
            WatermarkManager wmC1 = new WatermarkManager(allocatorC1);
            wmC1.refresh();
            ResourceArbiter arbiterC1 = ResourceArbiterTestHelper.expiredGraceArbiter(wmC1);
            AdmissionController ctlC1 = new AdmissionController(arbiterC1);
            StreamLoadShedder shedderC1 = new StreamLoadShedder(ENGINE_NAME + ".C1");
            
            StreamHandler handlerC1 = (stream) -> {
                streamHandlerInvokeCount.incrementAndGet();
                stream.close();
            };
            Function<TransportStream, StreamPriority> priorityC1 = 
                    (stream) -> StreamPriority.NORMAL;
            
            schedulerC1DefaultVt = new PaqsScheduler(
                    ctlC1, shedderC1, handlerC1, priorityC1, ENGINE_NAME + ".C1"
            );
        }

        // C2: Isolated infrastructure with inline execution backend
        {
            MemoryAllocator allocatorC2 = stubAllocator(NORMAL_ALLOCATED, TOTAL_HEAP);
            WatermarkManager wmC2 = new WatermarkManager(allocatorC2);
            wmC2.refresh();
            ResourceArbiter arbiterC2 = ResourceArbiterTestHelper.expiredGraceArbiter(wmC2);
            AdmissionController ctlC2 = new AdmissionController(arbiterC2);
            StreamLoadShedder shedderC2 = new StreamLoadShedder(ENGINE_NAME + ".C2");
            
            StreamHandler handlerC2 = (stream) -> {
                streamHandlerInvokeCount.incrementAndGet();
                stream.close();
            };
            Function<TransportStream, StreamPriority> priorityC2 = 
                    (stream) -> StreamPriority.NORMAL;
            StreamExecutionBackend inlineBackend = (threadName, task) -> task.run();
            
            schedulerC2InlineExec = new PaqsScheduler(
                    ctlC2, shedderC2, handlerC2, priorityC2, ENGINE_NAME + ".C2", inlineBackend
            );
        }

        // C4: Isolated infrastructure with locality-aware backend
        {
            MemoryAllocator allocatorC4 = stubAllocator(NORMAL_ALLOCATED, TOTAL_HEAP);
            WatermarkManager wmC4 = new WatermarkManager(allocatorC4);
            wmC4.refresh();
            ResourceArbiter arbiterC4 = ResourceArbiterTestHelper.expiredGraceArbiter(wmC4);
            AdmissionController ctlC4 = new AdmissionController(arbiterC4);
            StreamLoadShedder shedderC4 = new StreamLoadShedder(ENGINE_NAME + ".C4");
            
            StreamHandler handlerC4 = (stream) -> {
                streamHandlerInvokeCount.incrementAndGet();
                stream.close();
            };
            Function<TransportStream, StreamPriority> priorityC4 = 
                    (stream) -> StreamPriority.NORMAL;
            
            localityPool = new ForkJoinPool(Runtime.getRuntime().availableProcessors());
            StreamExecutionBackend localityBackend = this::startLocalityPinnedVirtualThread;
            
            schedulerC4LocalityAware = new PaqsScheduler(
                    ctlC4, shedderC4, handlerC4, priorityC4, ENGINE_NAME + ".C4", localityBackend
            );
        }
    }

    /**
     * Shuts down all scheduler variants and dumps JFR if enabled.
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
        
        if (JFR_ENABLED && jfrRecording != null) {
            try {
                jfrRecording.stop();
                jfrRecording.close();
            } catch (Exception e) {
                // Best-effort JFR cleanup
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
