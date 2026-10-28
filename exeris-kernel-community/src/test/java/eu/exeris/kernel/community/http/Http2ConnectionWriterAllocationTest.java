/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import com.sun.management.ThreadMXBean;
import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.core.http.http2.Http2FlowController;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.function.IntConsumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The frame-loop paths of {@link Http2ConnectionWriter} run for every WINDOW_UPDATE, SETTINGS and
 * RST_STREAM a peer sends, so each states, and is held to, allocating nothing. Bytes are exact
 * per-thread counts from {@link ThreadMXBean#getCurrentThreadAllocatedBytes()}; a path that does
 * allocate is measured too, so a counter that reads zero for everything cannot pass the other cases.
 */
@DisplayName("Http2ConnectionWriter: frame-loop paths allocate nothing")
class Http2ConnectionWriterAllocationTest {

    private static final ThreadMXBean THREADS = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
    /**
     * What the counter may show over a whole measured run with nothing allocated by the code: JIT
     * and counter granularity. One boxed id per call would show well over a megabyte.
     */
    private static final long NOISE_BYTES = 4096;
    private static final int WARMUP = 20_000;
    private static final int MEASURED = 100_000;
    private static final int KNOWN_STREAM = 1001;
    private static final int UNKNOWN_STREAM = 2001;

    @BeforeAll
    static void requireCounter() {
        // Asserted rather than assumed: a skipped allocation case would report green while
        // measuring nothing.
        assertThat(THREADS.isThreadAllocatedMemorySupported())
                .as("per-thread allocation counting is what these cases measure with")
                .isTrue();
        THREADS.setThreadAllocatedMemoryEnabled(true);
    }

    @AfterAll
    @SuppressWarnings("unused")
    static void closeAllocator() {
        ALLOCATOR.close();
    }

    private static Http2ConnectionWriter writerWithOpenStream() {
        Http2ConnectionWriter writer = Http2ConnectionWriter.create(
                new Http2ScriptedTransport(), ALLOCATOR, 100, 64 * 1024);
        writer.register(KNOWN_STREAM, 0);
        return writer;
    }

    @Test
    @DisplayName("a connection WINDOW_UPDATE allocates nothing")
    void connectionWindowUpdateAllocatesNothing() {
        Http2ConnectionWriter writer = writerWithOpenStream();

        assertThat(bytesOverRun(i -> writer.applyConnectionWindowUpdate(1))).isLessThan(NOISE_BYTES);
    }

    @Test
    @DisplayName("a stream WINDOW_UPDATE allocates nothing, for a tracked and for an unknown stream")
    void streamWindowUpdateAllocatesNothing() {
        Http2ConnectionWriter writer = writerWithOpenStream();

        assertThat(bytesOverRun(i -> writer.applyStreamWindowUpdate(KNOWN_STREAM, 1)))
                .as("tracked stream").isLessThan(NOISE_BYTES);
        assertThat(bytesOverRun(i -> writer.applyStreamWindowUpdate(UNKNOWN_STREAM, 1)))
                .as("unknown stream").isLessThan(NOISE_BYTES);
    }

    @Test
    @DisplayName("a SETTINGS_INITIAL_WINDOW_SIZE change allocates nothing")
    void initialWindowSizeAllocatesNothing() {
        Http2ConnectionWriter writer = writerWithOpenStream();

        assertThat(bytesOverRun(i -> writer.applyInitialWindowSize(
                Http2FlowController.DEFAULT_WINDOW_SIZE + (i & 1)))).isLessThan(NOISE_BYTES);
    }

    @Test
    @DisplayName("a peer reset of a stream that is not tracked allocates nothing")
    void peerResetOfUnknownStreamAllocatesNothing() {
        Http2ConnectionWriter writer = writerWithOpenStream();

        assertThat(bytesOverRun(i -> writer.resetByPeer(UNKNOWN_STREAM))).isLessThan(NOISE_BYTES);
    }

    @Test
    @DisplayName("registering and releasing a response stream allocates, so the counter can see it")
    void registerAndReleaseAllocates() {
        Http2ConnectionWriter writer = writerWithOpenStream();

        assertThat(bytesOverRun(i -> writer.release(writer.register(UNKNOWN_STREAM, 0))))
                .isGreaterThan(0L);
    }

    /** Total bytes allocated over {@code MEASURED} calls, not a per-call average that would round a small leak to zero. */
    private static long bytesOverRun(IntConsumer call) {
        for (int i = 0; i < WARMUP; i++) {
            call.accept(i);
        }
        long before = THREADS.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < MEASURED; i++) {
            call.accept(i);
        }
        return THREADS.getCurrentThreadAllocatedBytes() - before;
    }
}
