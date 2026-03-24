/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.transport.TransportConfig;
import eu.exeris.kernel.spi.transport.TransportConnection;
import eu.exeris.kernel.spi.transport.TransportMode;
import eu.exeris.kernel.spi.transport.TransportStream;
import eu.exeris.kernel.tck.perf.AbstractExerisBenchmark;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * C4 benchmark for fixed-rate stream writes across default VT and locality-aware backends.
 */
@State(Scope.Thread)
public class CommunityTransportFixedRateBenchmark extends AbstractExerisBenchmark {

    private static final String LOOPBACK = "127.0.0.1";
    private static final String PROVIDER_ID = "community-transport";
    private static final AtomicInteger PORT_BASE = new AtomicInteger(21_000);
    private static final long UNPACED_OPS_SENTINEL = -1L;
    private static final boolean JFR_ENABLED = Boolean.parseBoolean(
            System.getProperty("exeris.benchmark.jfr.enabled", "true"));
    private static final String ARTIFACT_BASE = System.getProperty(
            "exeris.benchmark.artifacts.dir", "target/benchmark-artifacts");

    @Param({
            "h1-plaintext-fixed-rate",
            "h1-json-1kb-fixed-rate",
            "h2-plaintext-fixed-rate",
            "h2-json-1kb-fixed-rate"
    })
    public String scenario;

    @Param({"default-vt", "locality-aware"})
    public String backendMode;

    @Param({"sub-max", "moderate", "max-throughput"})
    public String loadProfile;

    @Param({"16", "32", "64"})
    public int concurrency;

    private MemoryAllocator allocator;
    private NativeTcpCarrier serverCarrier;
    private NativeTcpCarrier clientCarrier;
    private TransportConnection activeConnection;
    private List<TransportStream> streamPool;
    private AtomicInteger streamRoundRobinIndex;

    private byte[] payloadBytes;
    private MemorySegment payloadSegment;
    private int payloadSize;

    private long targetOpsPerSecond;
    private long nextWriteNanos;
    private jdk.jfr.Recording jfrRecording;
    private Path artifactDir;
    private String jfrFilePath;

    @Setup(Level.Trial)
    public void setupTrial() {
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
        
        allocator = new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
        int port = PORT_BASE.getAndIncrement();

        serverCarrier = createCarrier(new TransportConfig(
                TransportMode.SERVER,
                LOOPBACK,
                port,
                1,
                null,
                null,
                50_000,
                10_000L
        ));
        serverCarrier.setStreamHandler(stream -> {
            try (stream; LoanedBuffer sink = allocator.allocateNetwork(8_192)) {
                while (true) {
                    int bytesRead = stream.read(sink.segment(), (int) sink.segment().byteSize());
                    if (bytesRead < 0) {
                        return;
                    }
                    if (bytesRead == 0) {
                        Thread.onSpinWait();
                    }
                }
            } catch (RuntimeException _) {
                // Ignore shutdown-path read failures during benchmark teardown.
            }
        });
        serverCarrier.start();

        clientCarrier = createCarrier(new TransportConfig(
                TransportMode.CLIENT,
                null,
                0,
                1,
                null,
                null,
                50_000,
                10_000L
        ));

        if ("locality-aware".equals(backendMode)
                && (!serverCarrier.isLocalityBackendActive() || !clientCarrier.isLocalityBackendActive())) {
            throw new IllegalStateException("Locality backend not active");
        }

        clientCarrier.start();

        activeConnection = clientCarrier.connect(LOOPBACK, port);
        
        // Initialize stream pool with concurrency size, round-robin indexed
        streamPool = new ArrayList<>(concurrency);
        for (int i = 0; i < concurrency; i++) {
            streamPool.add(activeConnection.openStream());
        }
        streamRoundRobinIndex = new AtomicInteger(0);

        payloadBytes = payloadForScenario(scenario);
        payloadSegment = MemorySegment.ofArray(payloadBytes);
        payloadSize = payloadBytes.length;
        targetOpsPerSecond = targetOpsForProfile(loadProfile);
    }

    /**
     * Preflight validation: verify backend path is available.
     * Fail fast if backend unavailable.
     */
    private void preflight() {
        if ("locality-aware".equals(backendMode)) {
            try {
                Class<?> carrierClass = Class.forName(
                        "eu.exeris.kernel.community.transport.NativeTcpCarrier");
                if (carrierClass == null) {
                    throw new IllegalStateException("NativeTcpCarrier backend not available");
                }
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(
                        "Preflight validation failed: locality backend path unavailable", e);
            }
        }
    }

    /**
     * Initialize per-trial artifact directory and manifest.
     */
    private void initializeArtifacts() {
        try {
            artifactDir = Files.createDirectories(Paths.get(ARTIFACT_BASE)
                    .resolve("trial-" + System.currentTimeMillis()));
            
            String jfrFileName = JFR_ENABLED ? 
                    "community-fixed-rate-c" + concurrency + "-" + System.nanoTime() + ".jfr" : null;
            jfrFilePath = JFR_ENABLED ? artifactDir.resolve(jfrFileName).toString() : null;
            
            String manifestJson = buildManifest(backendMode, scenario, loadProfile, jfrFileName);
            Files.write(artifactDir.resolve("manifest.json"),
                    manifestJson.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize benchmark artifacts", e);
        }
    }

    /**
     * Build JSON manifest with benchmark metadata.
     */
    private String buildManifest(String backend, String scenario, String profile, String jfrFileName) {
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"benchmarkName\":\"CommunityTransportFixedRateBenchmark\",");
        json.append("\"backendMode\":\"").append(backend).append("\",");
        json.append("\"scenario\":\"").append(scenario).append("\",");
        json.append("\"loadProfile\":\"").append(profile).append("\",");
        json.append("\"concurrency\":").append(concurrency).append(",");
        json.append("\"timestamp\":").append(System.currentTimeMillis()).append(",");
        json.append("\"jfrEnabled\":").append(JFR_ENABLED).append(",");
        json.append("\"jfrFile\":").append(jfrFileName != null ? "\"" + jfrFileName + "\"" : "null").append(",");
        json.append("\"perfStatMode\":\"external-runner\",");
        json.append("\"perfStatIntegrated\":false");
        json.append("}");
        return json.toString();
    }

    @Setup(Level.Iteration)
    public void resetPacerState() {
        nextWriteNanos = System.nanoTime();
    }

    @Benchmark
    public void fixedRateStreamWrite(Blackhole bh) {
        paceIfRequired();

        int streamIdx = streamRoundRobinIndex.getAndIncrement() % concurrency;
        TransportStream stream = streamPool.get(streamIdx);
        
        try (LoanedBuffer payloadBuffer = allocator.allocateNetwork(payloadSize)) {
            payloadBuffer.segment().asSlice(0, payloadSize).copyFrom(payloadSegment);
            stream.write(payloadBuffer.segment(), payloadSize);
            bh.consume(payloadSize);
        }
    }

    @TearDown(Level.Trial)
    public void tearDownTrial() {
        if (streamPool != null) {
            for (TransportStream stream : streamPool) {
                closeQuietly(stream);
            }
        }
        closeQuietly(activeConnection);
        closeQuietly(clientCarrier);
        closeQuietly(serverCarrier);
        closeQuietly(allocator);
        
        if (JFR_ENABLED && jfrRecording != null) {
            try {
                jfrRecording.stop();
                jfrRecording.close();
            } catch (Exception e) {
                // Best-effort JFR cleanup
            }
        }
    }

    private NativeTcpCarrier createCarrier(TransportConfig config) {
        if ("locality-aware".equals(backendMode)) {
            int localityPoolParallelism = Math.max(1, Runtime.getRuntime().availableProcessors());
            return new NativeTcpCarrier(config, allocator, null, null, PROVIDER_ID, localityPoolParallelism, true);
        }
        return new NativeTcpCarrier(config, allocator, null, null, PROVIDER_ID);
    }

    private void paceIfRequired() {
        if (targetOpsPerSecond == UNPACED_OPS_SENTINEL) {
            return;
        }
        long now = System.nanoTime();
        if (now < nextWriteNanos) {
            LockSupport.parkNanos(nextWriteNanos - now);
        }
        long periodNanos = 1_000_000_000L / targetOpsPerSecond;
        long base = Math.max(nextWriteNanos, System.nanoTime());
        nextWriteNanos = base + periodNanos;
    }

    private static byte[] payloadForScenario(String benchmarkScenario) {
        if (benchmarkScenario.contains("json-1kb")) {
            return jsonPayload1Kb().getBytes(StandardCharsets.UTF_8);
        }
        return "exeris-fixed-rate".getBytes(StandardCharsets.UTF_8);
    }

    private static long targetOpsForProfile(String profile) {
        return switch (profile) {
            case "sub-max" -> 100_000L;
            case "moderate" -> 500_000L;
            case "max-throughput" -> UNPACED_OPS_SENTINEL;
            default -> throw new IllegalArgumentException("Unsupported loadProfile: " + profile);
        };
    }

    private static String jsonPayload1Kb() {
        StringBuilder builder = new StringBuilder(1_024);
        builder.append('{').append("\"msg\":\"");
        while (builder.length() < 1_012) {
            builder.append("exeris-payload-");
        }
        builder.setLength(1_012);
        builder.append("\",\"type\":\"benchmark\"}");
        return builder.toString();
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception _) {
            // Best-effort benchmark cleanup.
        }
    }
}
