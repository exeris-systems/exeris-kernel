/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.community.transport.NativeTcpTransportProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpStreamHandler;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.http.StreamEvent;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.transport.StreamHandler;
import eu.exeris.kernel.spi.transport.TransportConfig;
import eu.exeris.kernel.spi.transport.TransportEngine;
import eu.exeris.kernel.spi.transport.TransportMode;
import jdk.jfr.consumer.RecordingStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts the four ADR-043 streaming JFR events fire single-phase on their respective paths
 * (open / graceful-close / abortive-teardown / backpressure-park). Lives in the Community test tree,
 * not the SPI-only TCK (ADR-043 Engineering Protocol §4).
 *
 * <p>Single-phase commit is asserted structurally: the events carry {@code @StackTrace(false)} and are
 * emitted by {@code HttpStreamEngine} with no blocking operation between construct and commit (the park
 * event is committed BEFORE the park). A straddle would SIGSEGV under JDK 26 rather than fail soft, so a
 * green run on the streaming VT is itself the single-phase proof.
 */
@Tag("stream-loopback")
@DisplayName("Community: HTTP streaming JFR events")
class CommunityHttpStreamJfrTest {

    private static final String OPENED = "eu.exeris.kernel.http.StreamOpened";
    private static final String CLOSED = "eu.exeris.kernel.http.StreamClosed";
    private static final String ABORTIVE = "eu.exeris.kernel.http.StreamAbortiveTeardown";
    private static final String PARK = "eu.exeris.kernel.http.StreamBackpressurePark";

    private static final long RECORD_SETTLE_MILLIS = 1_500L;
    private static final long PARK_SLICE_MILLIS = 5L;
    private static final int CLIENT_READ_BUF = 16 * 1024;

    static {
        // Small credit window + send buffer so the backpressure-park path fires deterministically.
        System.setProperty("exeris.http.stream.creditWindowBytes", Integer.toString(16 * 1024));
        System.setProperty("exeris.transport.acceptedSendBufferBytes", Integer.toString(8 * 1024));
    }

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    @Test
    @Timeout(value = 30L, unit = TimeUnit.SECONDS)
    @DisplayName("graceful open→emit→close fires StreamOpened + StreamClosed")
    void gracefulFiresOpenAndClose() throws Exception {
        Set<String> seen = ConcurrentHashMap.newKeySet();
        try (RecordingStream recording = startRecording(seen)) {
            runGraceful();
            settle();
        }
        assertThat(seen).contains(OPENED, CLOSED);
    }

    @Test
    @Timeout(value = 30L, unit = TimeUnit.SECONDS)
    @DisplayName("client disconnect fires StreamAbortiveTeardown")
    void disconnectFiresAbortiveTeardown() throws Exception {
        Set<String> seen = ConcurrentHashMap.newKeySet();
        try (RecordingStream recording = startRecording(seen)) {
            runDisconnect();
            settle();
        }
        assertThat(seen).contains(OPENED, ABORTIVE);
    }

    @Test
    @Timeout(value = 30L, unit = TimeUnit.SECONDS)
    @DisplayName("stalled client fires StreamBackpressurePark")
    void stalledClientFiresBackpressurePark() throws Exception {
        Set<String> seen = ConcurrentHashMap.newKeySet();
        try (RecordingStream recording = startRecording(seen)) {
            runBackpressure();
            settle();
        }
        assertThat(seen).contains(OPENED, PARK);
    }

    private static RecordingStream startRecording(Set<String> seen) {
        RecordingStream recording = new RecordingStream();
        recording.enable(OPENED);
        recording.enable(CLOSED);
        recording.enable(ABORTIVE);
        recording.enable(PARK);
        recording.onEvent(event -> seen.add(event.getEventType().getName()));
        recording.startAsync();
        return recording;
    }

    private static void settle() {
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(RECORD_SETTLE_MILLIS));
    }

    // -------------------------------------------------------------------------
    // Scenarios
    // -------------------------------------------------------------------------

    private void runGraceful() {
        HttpStreamHandler handler = exchange -> {
            exchange.emit(StreamEvent.of("tick", "0"));
            exchange.emit(StreamEvent.of("tick", "1"));
            exchange.close();
        };
        try (Loopback loop = Loopback.start(handler, false)) {
            loop.awaitHandlerDone();
        }
    }

    private void runDisconnect() {
        HttpStreamHandler handler = exchange -> {
            while (true) {
                exchange.emit(StreamEvent.of("x"));
            }
        };
        try (Loopback loop = Loopback.start(handler, false)) {
            loop.readSome();
            loop.disconnect();
            loop.awaitHandlerDone();
        }
    }

    private void runBackpressure() {
        int flood = 10_000;
        HttpStreamHandler handler = exchange -> {
            for (int idx = 0; idx < flood; idx++) {
                exchange.emit(StreamEvent.of(Integer.toString(idx)));
            }
            exchange.close();
        };
        try (Loopback loop = Loopback.start(handler, true)) {
            // Client never reads (stalled): the egress window fills and the emitter parks.
            loop.awaitHandlerDone();
        }
    }

    // -------------------------------------------------------------------------
    // Minimal loopback: real NIO server + plain socket; optionally non-reading.
    // -------------------------------------------------------------------------

    private static final class Loopback implements AutoCloseable {

        private final TransportEngine serverEngine;
        private final Socket client;
        private final AtomicBoolean handlerDone = new AtomicBoolean(false);

        private Loopback(TransportEngine serverEngine, Socket client) {
            this.serverEngine = serverEngine;
            this.client = client;
        }

        @SuppressWarnings("PMD.CloseResource")
        static Loopback start(HttpStreamHandler handler, boolean stalledClient) {
            int port = nextFreePort();
            CommunityHttpStreamDispatcher dispatcher =
                    new CommunityHttpStreamDispatcher(new LeakTrackingAllocator(ALLOCATOR));
            TransportEngine serverEngine = createServerEngine(port);
            Loopback[] self = new Loopback[1];
            StreamHandler streamHandler = serverStream -> {
                try {
                    dispatcher.dispatchStream(
                            new HttpRequest(HttpMethod.GET, "/stream", HttpVersion.HTTP_1_1, List.of(), null),
                            serverStream, handler);
                } finally {
                    self[0].handlerDone.set(true);
                }
            };
            serverEngine.setStreamHandler(streamHandler);
            serverEngine.setConnectionHandler(connection -> { });
            serverEngine.start();
            Socket client = connectClient(port);
            Loopback loop = new Loopback(serverEngine, client);
            self[0] = loop;
            if (!stalledClient) {
                startDrainer(client);
            }
            return loop;
        }

        @SuppressWarnings("PMD.AvoidCatchingGenericException")
        private static void startDrainer(Socket client) {
            // Platform thread: blocking java.net.Socket reads can pin a carrier and the JVM forces only
            // 2 VT carriers here.
            Thread.ofPlatform().daemon(true).name("jfr-drainer").start(() -> {
                byte[] buf = new byte[CLIENT_READ_BUF];
                try {
                    while (client.getInputStream().read(buf) >= 0) {
                        // drain
                    }
                } catch (IOException | RuntimeException _) {
                    // disconnect / close
                }
            });
        }

        void readSome() {
            byte[] buf = new byte[CLIENT_READ_BUF];
            try {
                client.getInputStream().read(buf);
            } catch (IOException _) {
                // best effort
            }
        }

        void disconnect() {
            try {
                client.close();
            } catch (IOException _) {
                // best effort
            }
        }

        void awaitHandlerDone() {
            long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(20L);
            while (!handlerDone.get() && System.currentTimeMillis() < deadline) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(PARK_SLICE_MILLIS));
            }
        }

        @Override
        public void close() {
            try {
                client.close();
            } catch (IOException _) {
                // best effort
            }
            try {
                serverEngine.close();
            } catch (RuntimeException _) {
                // best effort
            }
        }
    }

    private static TransportEngine createServerEngine(int port) {
        TransportEngine[] holder = new TransportEngine[1];
        ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, ALLOCATOR).run(() ->
                holder[0] = new NativeTcpTransportProvider().createEngine(new TransportConfig(
                        TransportMode.SERVER, "127.0.0.1", port, 1, null, null, 1024, 30_000)));
        return holder[0];
    }

    private static Socket connectClient(int port) {
        try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", port), (int) TimeUnit.SECONDS.toMillis(5L));
            socket.setTcpNoDelay(true);
            socket.getOutputStream().write(
                    "GET /stream HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            return socket;
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to connect loopback client", ex);
        }
    }

    private static int nextFreePort() {
        try (ServerSocketChannel server = ServerSocketChannel.open()) {
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            return ((InetSocketAddress) Objects.requireNonNull(server.getLocalAddress())).getPort();
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to allocate free TCP port", ex);
        }
    }
}
