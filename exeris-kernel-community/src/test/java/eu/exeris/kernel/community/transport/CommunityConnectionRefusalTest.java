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
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.transport.TransportConfig;
import eu.exeris.kernel.spi.transport.TransportEngine;
import eu.exeris.kernel.spi.transport.TransportMode;
import eu.exeris.kernel.spi.transport.TransportStats;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A connection refused at the {@code maxConnections} ceiling must be visible.
 *
 * <p>Before this, the refusal closed an already-accepted socket with no log line and no event, and
 * {@code TransportStats.totalRejected} counted only PAQS load-sheds — so the one field an operator
 * would consult read zero while the carrier turned every connection away. That is worse than no
 * telemetry: a zero is read as evidence the fault lies elsewhere.
 *
 * <p>The cap is set to 2 so the ceiling is reachable in a unit test without opening a thousand
 * sockets. Nothing about the mechanism is size-dependent.
 */
@DisplayName("NativeTcpCarrier — refusing a connection at the maxConnections ceiling is observable")
class CommunityConnectionRefusalTest {

    private static final String REFUSED = "eu.exeris.kernel.transport.CommunityConnectionRefused";

    private static final int MAX_CONNECTIONS = 2;
    private static final int OVER_CEILING = 4;
    private static final int CONNECT_TIMEOUT_MS = 2_000;
    private static final long SETTLE_TIMEOUT_SECONDS = 5L;

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    @Test
    @DisplayName("commits a refusal event and counts it into TransportStats.totalRejected")
    void refusalIsCountedAndRecorded(@TempDir Path tmp) throws Exception {
        Path jfr = tmp.resolve("refusal.jfr");
        List<Socket> sockets = new ArrayList<>();
        TransportEngine engine = null;

        try (Recording recording = new Recording()) {
            recording.enable(REFUSED);
            recording.start();

            int port = CommunityTransportTestHarness.nextFreePort();
            engine = startEngine(port);

            // One more than the ceiling: the surplus is accepted at TCP level and then closed.
            for (int i = 0; i < OVER_CEILING; i++) {
                sockets.add(openQuietly(port));
            }
            awaitRefusal(engine);

            recording.stop();
            recording.dump(jfr);
        } finally {
            sockets.forEach(CommunityConnectionRefusalTest::closeQuietly);
            if (engine != null) {
                engine.stop();
            }
        }

        List<RecordedEvent> events = RecordingFile.readAllEvents(jfr).stream()
                .filter(e -> REFUSED.equals(e.getEventType().getName()))
                .toList();

        assertThat(events)
                .as("a refusal that emits nothing is indistinguishable from a healthy server")
                .isNotEmpty();

        RecordedEvent first = events.getFirst();
        assertThat(first.getInt("maxConnections")).isEqualTo(MAX_CONNECTIONS);
        assertThat(first.getLong("activeConnections")).isEqualTo(MAX_CONNECTIONS);
        assertThat(first.getLong("totalRefused")).isPositive();
        assertThat(first.getString("bindAddress")).isEqualTo("127.0.0.1");
    }

    private static void awaitRefusal(TransportEngine engine) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SETTLE_TIMEOUT_SECONDS);
        while (engine.stats().totalRejected() == 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        TransportStats stats = engine.stats();
        assertThat(stats.totalRejected())
                .as("TransportStats.totalRejected is the field an operator consults when asking "
                        + "whether the server is turning work away; an accept-time refusal is the "
                        + "most total form of that and used to be missing from it entirely")
                .isPositive();
    }

    private static TransportEngine startEngine(int port) {
        TransportEngine[] holder = new TransportEngine[1];
        ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, ALLOCATOR).run(() -> {
            holder[0] = new NativeTcpTransportProvider().createEngine(new TransportConfig(
                    TransportMode.SERVER, "127.0.0.1", port, 1, null, null, MAX_CONNECTIONS, 30_000));
            // Accepted connections are parked, never served: this exercises the accept-time ceiling,
            // and a handler that read or replied would let sockets close and free slots underneath it.
            holder[0].setStreamHandler(stream -> { });
            holder[0].start();
        });
        return holder[0];
    }

    private static Socket openQuietly(int port) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS);
        } catch (IOException e) {
            // A refused connection may surface here or on first read; either is the behaviour under
            // test, and the assertions read the server side rather than guessing which.
        }
        return socket;
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            // Test teardown.
        }
    }
}
