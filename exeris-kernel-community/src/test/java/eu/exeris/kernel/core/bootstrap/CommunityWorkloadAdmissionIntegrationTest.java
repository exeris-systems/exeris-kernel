/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.bootstrap;

import eu.exeris.kernel.core.contract.ContractDecision;
import eu.exeris.kernel.core.contract.DeveloperWorkloadAdmission;
import eu.exeris.kernel.spi.bootstrap.BootstrapSelector;
import eu.exeris.kernel.spi.contract.ExecutionContract;
import eu.exeris.kernel.spi.contract.WorkloadAdmission;
import eu.exeris.kernel.spi.contract.WorkloadEnvelope;
import eu.exeris.kernel.spi.time.TimeSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The gate the kernel binds reaches the Community HTTP driver's own threads: a contract gate that
 * decides a developer envelope makes the real HTTP provider, booted through {@code KernelBootstrap},
 * stamp the licence marker, refuse a request past the ceiling and refuse a connection past the ceiling.
 *
 * <p>The suite lives in this package because the contract gate is replaceable only from here. Its clock
 * is held fixed, so the request ceiling is exactly the one-second burst and no timing decides a case.
 */
@Tag("integration")
@Timeout(60)
@DisplayName("IT: the developer workload gate bound by KernelBootstrap reaches the Community HTTP driver")
class CommunityWorkloadAdmissionIntegrationTest {

    private static final String HOST = "127.0.0.1";
    private static final String MARKER = "exeris-license: development";

    @Test
    @DisplayName("the marker is on every response and a request past the ceiling is answered 429")
    void requestCeilingAndMarker() throws Exception {
        int port = nextFreePort();
        boot(port, new WorkloadEnvelope(3, 100, 0), () -> {
            try (Socket client = connect(port)) {
                for (int i = 1; i <= 3; i++) {
                    String reply = get(client, "/health");
                    assertThat(reply).as("request %d is within the burst", i).startsWith("HTTP/1.1 200");
                    assertThat(headerLines(reply)).as("request %d", i).containsOnlyOnce(MARKER);
                }
                String refused = get(client, "/health");
                assertThat(refused).startsWith("HTTP/1.1 429");
                assertThat(headerLines(refused)).contains("retry-after: 1").containsOnlyOnce(MARKER);
            }
        });
    }

    @Test
    @DisplayName("a connection past the ceiling is closed with no byte read, and a closed one frees its place")
    void connectionCeiling() throws Exception {
        int port = nextFreePort();
        ExecutionContract contract = ExecutionContract.communityFallback();
        ReleaseSignal gate = new ReleaseSignal(new DeveloperWorkloadAdmission(
                contract.contractId(), new WorkloadEnvelope(1_000, 2, 0), new HeldClock()));
        bootWith(port, gate, () -> {
            Socket first = connect(port);
            Socket second = connect(port);
            try {
                assertThat(get(first, "/health")).startsWith("HTTP/1.1 200");
                assertThat(get(second, "/health")).startsWith("HTTP/1.1 200");

                try (Socket third = connect(port)) {
                    assertClosedWithNoBytes(third);
                }

                first.close();
                assertThat(gate.released.await(10, TimeUnit.SECONDS))
                        .as("the closed connection's admission is released").isTrue();
                try (Socket fourth = connect(port)) {
                    assertThat(get(fourth, "/health")).startsWith("HTTP/1.1 200");
                }
            } finally {
                first.close();
                second.close();
            }
        });
    }

    /** A time source that never moves, so the request ceiling is exactly the one-second burst. */
    private static final class HeldClock implements TimeSource {

        @Override
        public long nanoTime() {
            return 0L;
        }

        @Override
        public Instant wallTime() {
            return Instant.EPOCH;
        }
    }

    /** Delegates to a real gate and announces the first release, so a case waits on it instead of polling. */
    private static final class ReleaseSignal implements WorkloadAdmission {

        private final WorkloadAdmission delegate;
        private final CountDownLatch released = new CountDownLatch(1);

        ReleaseSignal(WorkloadAdmission delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean enforced() {
            return delegate.enforced();
        }

        @Override
        public boolean tryAdmitConnection() {
            return delegate.tryAdmitConnection();
        }

        @Override
        public void connectionClosed() {
            delegate.connectionClosed();
            released.countDown();
        }

        @Override
        public boolean tryAdmitRequest() {
            return delegate.tryAdmitRequest();
        }
    }

    @Test
    @DisplayName("UNGATED decided by the contract gate leaves the same load with no marker and no 429")
    void ungatedDecisionEnforcesNothing() throws Exception {
        int port = nextFreePort();
        bootWith(port, WorkloadAdmission.UNGATED, () -> {
            try (Socket client = connect(port)) {
                for (int i = 1; i <= 10; i++) {
                    String reply = get(client, "/health");
                    assertThat(reply).as("request %d", i).startsWith("HTTP/1.1 200");
                    assertThat(headerLines(reply)).as("request %d", i).doesNotContain(MARKER);
                }
            }
        });
    }

    private static void boot(int port, WorkloadEnvelope envelope, ThrowingRunnable body) throws Exception {
        ExecutionContract contract = ExecutionContract.communityFallback();
        bootWith(port,
                new DeveloperWorkloadAdmission(contract.contractId(), envelope, new HeldClock()),
                body);
    }

    private static void bootWith(int port, WorkloadAdmission admission, ThrowingRunnable body) throws Exception {
        String[] keys = {"exeris.http.mode", "exeris.http.bindHost", "exeris.http.port"};
        String[] values = {"SERVER", HOST, Integer.toString(port)};
        String[] previous = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            previous[i] = System.getProperty(keys[i]);
            System.setProperty(keys[i], values[i]);
        }
        try {
            ExecutionContract contract = ExecutionContract.communityFallback();
            Throwable[] failure = new Throwable[1];
            KernelBootstrap.builder()
                    .selector(BootstrapSelector.forNames("http"))
                    .contractGate((config, loader) -> new ContractDecision(contract, admission))
                    .build()
                    .boot(() -> {
                        try {
                            body.run();
                        } catch (Throwable t) {
                            failure[0] = t;
                        }
                    });
            if (failure[0] instanceof Exception e) {
                throw e;
            }
            if (failure[0] instanceof Error e) {
                throw e;
            }
        } finally {
            for (int i = 0; i < keys.length; i++) {
                if (previous[i] == null) {
                    System.clearProperty(keys[i]);
                } else {
                    System.setProperty(keys[i], previous[i]);
                }
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void assertClosedWithNoBytes(Socket socket) throws IOException {
        int read;
        try {
            read = socket.getInputStream().read();
        } catch (SocketException _) {
            // A reset is a close with no bytes either; a read that timed out is not caught and fails the case.
            read = -1;
        }
        assertThat(read).as("a refused connection is closed before the server writes a byte").isEqualTo(-1);
    }

    private static Socket connect(int port) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(HOST, port), 5_000);
        socket.setSoTimeout(10_000);
        return socket;
    }

    private static String get(Socket client, String path) throws IOException {
        OutputStream out = client.getOutputStream();
        out.write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.flush();
        InputStream in = client.getInputStream();
        StringBuilder head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) {
            int next = in.read();
            if (next < 0) {
                throw new EOFException("the connection closed before the response head ended: " + head);
            }
            head.append((char) next);
        }
        String text = head.toString();
        String lower = text.toLowerCase(Locale.ROOT);
        int field = lower.indexOf("\r\ncontent-length:");
        if (field >= 0) {
            int start = field + "\r\ncontent-length:".length();
            int length = Integer.parseInt(lower.substring(start, lower.indexOf("\r\n", start)).trim());
            in.readNBytes(length);
        }
        return text;
    }

    private static List<String> headerLines(String reply) {
        List<String> lines = new ArrayList<>();
        for (String line : reply.split("\r\n")) {
            lines.add(line.toLowerCase(Locale.ROOT));
        }
        return lines;
    }

    private static int nextFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(HOST, 0));
            return socket.getLocalPort();
        }
    }
}
