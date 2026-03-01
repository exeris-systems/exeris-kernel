/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.tck.perf;

import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.transport.TransportConfig;
import eu.exeris.kernel.spi.transport.TransportConnection;
import eu.exeris.kernel.spi.transport.TransportEngine;
import eu.exeris.kernel.spi.transport.TransportMode;
import eu.exeris.kernel.spi.transport.TransportProvider;
import eu.exeris.kernel.spi.transport.TransportStream;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

/**
 * TCK JMH Benchmark: Transport Stream Write Throughput (Ping-Pong).
 *
 * <h2>What is measured</h2>
 * <p>The pure L2 I/O overhead of queuing a native write on a pre-established
 * {@link TransportStream}. A loopback server and client are started once per
 * trial; the measurement window fires repeated write operations on the
 * already-open hot stream.
 *
 * <h2>Why this matters</h2>
 * <p>Community implementations queue writes through a standard Java NIO channel
 * backed by virtual threads. Enterprise implementations submit Submission Queue
 * Entries to an {@code io_uring} ring via Panama FFM — the entire write path is
 * syscall-free until the SQE ring is full. This benchmark forces the difference
 * to surface as raw ops/second.
 *
 * <h2>Zero-Copy contract</h2>
 * <p>The write buffer is a {@link LoanedBuffer} allocated through the kernel's
 * {@link MemoryAllocator}. This enforces the SPI's zero-copy contract:
 * no {@code ByteBuffer.allocateDirect} in the hot path — all off-heap memory
 * must flow through the tier-specific allocator.
 *
 * <h2>SPI alignment</h2>
 * <p>{@link TransportEngine#connect(String, int)} returns a {@link TransportConnection}
 * directly (blocking the virtual thread during the handshake). There is no
 * {@code CompletableFuture} in the SPI — the "1 VT per stream" model means that
 * blocking here simply unmounts the carrier without pinning it.
 *
 * <h2>Implementing this benchmark</h2>
 * <pre>{@code
 * public class MyCommunityTransportBenchmark
 *         extends AbstractTransportThroughputBenchmark {
 *
 *     \@Override
 *     protected TransportProvider getProvider() {
 *         return new CommunityTcpProvider();
 *     }
 *
 *     \@Override
 *     protected MemoryAllocator getAllocator() {
 *         return new CommunityHeapAllocator();
 *     }
 * }
 * }</pre>
 *
 * @since 0.5.0
 * @see AbstractExerisBenchmark
 */
public abstract class AbstractTransportThroughputBenchmark extends AbstractExerisBenchmark {

    /** Loopback address — avoids OS routing overhead in the measurement window. */
    private static final String LOOPBACK = "127.0.0.1";

    /**
     * Fixed benchmark port — bound to loopback for deterministic throughput measurement.
     * Using a fixed port ensures the server {@link TransportConfig} validation passes
     * (SERVER mode requires port in range 1–65535). If this port is already in use on
     * your host, override {@link #setupTransport()} to pick a free port.
     */
    private static final int BENCH_PORT = 19_999;

    /** Write payload size in bytes — 64 bytes targets a single cache line per write. */
    private static final int WRITE_BYTES = 64;

    /** Server-side engine — bound to {@value BENCH_PORT} on loopback. */
    protected TransportEngine serverEngine;

    /** Client-side engine — initiates the outbound connection. */
    protected TransportEngine clientEngine;

    /** The pre-established hot stream — used for every benchmark iteration. */
    protected TransportStream activeStream;

    /** The active connection backing {@link #activeStream} — closed explicitly in teardown. */
    protected TransportConnection activeConnection;

    /** Memory allocator for zero-copy write buffers. */
    protected MemoryAllocator allocator;

    /**
     * Implementations must return the {@link TransportProvider} under test.
     *
     * @return non-null provider (Community or Enterprise)
     */
    protected abstract TransportProvider getProvider();

    /**
     * Implementations must return a fully initialised {@link MemoryAllocator}
     * compatible with the transport provider (heap-backed for Community,
     * off-heap slab for Enterprise).
     *
     * @return non-null allocator
     */
    protected abstract MemoryAllocator getAllocator();

    /**
     * Trial-level setup: starts a loopback server, connects a client, and
     * opens the hot stream so the measurement window exercises only the
     * steady-state write path.
     *
     * <p>The server's {@link eu.exeris.kernel.spi.transport.StreamHandler} echoes
     * a single 64-byte write back on every incoming stream open — sufficient to
     * keep the connection alive without complicating the measurement with
     * realistic application logic.
     *
     * @throws Exception if the transport engines cannot bind or connect
     */
    @Setup(Level.Trial)
    public void setupTransport() throws Exception {
        this.allocator = getAllocator();
        TransportProvider provider = getProvider();

        // 1. Start server — binds to loopback:BENCH_PORT, minimal reactor count.
        TransportConfig serverConfig = new TransportConfig(
                TransportMode.SERVER, LOOPBACK, BENCH_PORT,
                1,           // single reactor is sufficient for a loopback echo server
                null, null,  // no TLS — plaintext loopback avoids crypto overhead in measurement
                1_000, 10_000L
        );
        this.serverEngine = provider.createEngine(serverConfig);
        // Sink handler: continuously read and discard incoming data to keep the TCP window open
        // and avoid backpressure. The stream is automatically closed via try-with-resources.
        this.serverEngine.setStreamHandler(stream -> {
            try (stream; LoanedBuffer sinkBuffer = allocator.allocateNetwork(8192)) {
                // Drain the stream until the client closes it or an exception occurs
                int bytesRead;
                while ((bytesRead = stream.read(sinkBuffer.segment(), 8192)) >= 0) {
                    // Consume the result to prevent dead-code elimination by the JIT
                    // and suppress "empty body" warnings.
                    if (bytesRead == 0) {
                        Thread.onSpinWait(); // Hint to CPU if we are spinning on a non-blocking read
                    }
                }
            } catch (Exception _) {
                // Ignore exceptions (like connection resets) during benchmark teardown
            }
        });
        this.serverEngine.start();

        // 2. Start client — CLIENT mode; port 0 is the sentinel for "not used".
        TransportConfig clientConfig = new TransportConfig(
                TransportMode.CLIENT, null, 0,
                1,
                null, null,
                1_000, 10_000L
        );
        this.clientEngine = provider.createEngine(clientConfig);
        this.clientEngine.start();

        // 3. Establish hot connection — connect() blocks the virtual thread during
        //    the handshake. No CompletableFuture in the SPI: the "1 VT per stream"
        //    model unmounts the carrier without pinning it.
        this.activeConnection = this.clientEngine.connect(LOOPBACK, BENCH_PORT);
        this.activeStream = this.activeConnection.openStream();
    }

    /**
     * Trial-level teardown: closes the hot stream and connection first, then both engines,
     * then the allocator. Explicit ordering guarantees child resources are released before
     * their parent engines tear down native rings/buffers.
     */
    @TearDown(Level.Trial)
    public void tearDownTransport() {
        if (activeStream != null) {
            activeStream.close();
        }
        if (activeConnection != null) {
            activeConnection.close();
        }
        if (clientEngine != null) {
            clientEngine.close();
        }
        if (serverEngine != null) {
            serverEngine.close();
        }
        if (allocator != null) {
            allocator.close();
        }
    }

    /**
     * Hot-path benchmark — measures the overhead of queuing a zero-copy native write
     * and flushing it through the stream.
     *
     * <p>The {@link LoanedBuffer} is acquired from the kernel's {@link MemoryAllocator}
     * each iteration. For Enterprise implementations this is a VarHandle CAS from the
     * 64-byte slab tier — negligible overhead compared to the SQE submission. The
     * blackhole prevents the JIT from eliminating the segment materialisation as
     * dead code.
     *
     * @param bh JMH blackhole — prevents dead-code elimination of segment access
     */
    @Benchmark
    public void streamWriteOverhead(Blackhole bh) {
        try (LoanedBuffer buffer = allocator.allocateNetwork(WRITE_BYTES)) {
            // Write the segment directly — zero-copy path:
            // Enterprise: submits SQE to io_uring without copying between heap and off-heap.
            // Community: writes to NIO channel from the MemorySegment backing array.
            activeStream.write(buffer.segment(), WRITE_BYTES);
            bh.consume(buffer.segment());
        }
    }
}



