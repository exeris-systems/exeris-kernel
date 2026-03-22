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

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
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

    private MemoryAllocator allocator;
    private NativeTcpCarrier serverCarrier;
    private NativeTcpCarrier clientCarrier;
    private TransportConnection activeConnection;
    private TransportStream activeStream;

    private byte[] payloadBytes;
    private MemorySegment payloadSegment;
    private int payloadSize;

    private long targetOpsPerSecond;
    private long nextWriteNanos;

    @Setup(Level.Trial)
    public void setupTrial() {
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
        clientCarrier.start();

        activeConnection = clientCarrier.connect(LOOPBACK, port);
        activeStream = activeConnection.openStream();

        payloadBytes = payloadForScenario(scenario);
        payloadSegment = MemorySegment.ofArray(payloadBytes);
        payloadSize = payloadBytes.length;
        targetOpsPerSecond = targetOpsForProfile(loadProfile);
    }

    @Setup(Level.Iteration)
    public void resetPacerState() {
        nextWriteNanos = System.nanoTime();
    }

    @Benchmark
    public void fixedRateStreamWrite(Blackhole bh) {
        paceIfRequired();

        try (LoanedBuffer payloadBuffer = allocator.allocateNetwork(payloadSize)) {
            payloadBuffer.segment().asSlice(0, payloadSize).copyFrom(payloadSegment);
            activeStream.write(payloadBuffer.segment(), payloadSize);
            bh.consume(payloadSize);
        }
    }

    @TearDown(Level.Trial)
    public void tearDownTrial() {
        closeQuietly(activeStream);
        closeQuietly(activeConnection);
        closeQuietly(clientCarrier);
        closeQuietly(serverCarrier);
        closeQuietly(allocator);
    }

    private NativeTcpCarrier createCarrier(TransportConfig config) {
        if ("locality-aware".equals(backendMode)) {
            int localityPoolParallelism = Math.max(1, Runtime.getRuntime().availableProcessors());
            return new NativeTcpCarrier(config, allocator, null, null, PROVIDER_ID, localityPoolParallelism);
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
