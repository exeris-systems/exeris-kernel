/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.crypto.CryptoProviderConfig;
import eu.exeris.kernel.spi.crypto.KernelCryptoProvider;
import eu.exeris.kernel.spi.crypto.TlsEngine;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.transport.TransportConfig;
import eu.exeris.kernel.spi.transport.TransportMode;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A connection that is accepted and then fails during setup must leave a trace.
 *
 * <p>Before this, the accept loop caught {@code RuntimeException} around channel configuration,
 * remote-address resolution, stream construction and registration, and discarded it — producing the
 * same externally-visible symptom as a healthy server under no load. This is the class of failure
 * that is easiest to misattribute, because a repeating fault looks exactly like an intermittent
 * network problem from the client side.
 *
 * <h2>How the fault is induced</h2>
 * <p>Through {@code NativeTcpCarrier}'s existing constructor parameter: a {@link KernelCryptoProvider}
 * whose {@code createTlsEngine} throws. That call happens <em>per accepted connection</em> inside
 * {@code buildAcceptedStream}, so every accept faults deterministically.
 *
 * <p>Deliberately not a production test seam. An earlier attempt to trigger this from the client side
 * — connect with {@code SO_LINGER 0} and reset immediately, 300 times — produced <b>zero</b> faults,
 * because an accepted channel still resolves its remote address after the peer resets. The setup path
 * is simply not reachable from outside, which is precisely why it needed a signal of its own.
 */
@DisplayName("NativeTcpCarrier — a connection that fails during setup is recorded, not swallowed")
class CommunityAcceptFaultTest {

    private static final String ACCEPT_FAULT = "eu.exeris.kernel.transport.CommunityAcceptFault";

    private static final int CONNECT_TIMEOUT_MS = 2_000;
    private static final long SETTLE_TIMEOUT_SECONDS = 5L;
    private static final int ATTEMPTS = 3;

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    @Test
    @DisplayName("commits an accept-fault event carrying the exception class, and recovers")
    void acceptSetupFailureIsRecorded(@TempDir Path tmp) throws Exception {
        int port = freePort();
        NativeTcpCarrier carrier = new NativeTcpCarrier(
                new TransportConfig(TransportMode.SERVER, "127.0.0.1", port, 1,
                        "unused.pem", "unused.key", 1024, 30_000),
                ALLOCATOR,
                new ThrowingCryptoProvider(),
                CryptoProviderConfig.httpsServer(tmp.resolve("cert.pem"), tmp.resolve("key.pem")));
        carrier.setStreamHandler(stream -> { });

        Path jfr = tmp.resolve("accept-fault.jfr");
        try (Recording recording = new Recording()) {
            recording.enable(ACCEPT_FAULT);
            recording.start();

            carrier.start();
            for (int i = 0; i < ATTEMPTS; i++) {
                closeQuietly(connectQuietly(port));
            }
            awaitFaults(jfr, recording);
        } finally {
            carrier.close();
        }

        List<RecordedEvent> faults = RecordingFile.readAllEvents(jfr).stream()
                .filter(e -> ACCEPT_FAULT.equals(e.getEventType().getName()))
                .toList();

        assertThat(faults)
                .as("a setup failure that emits nothing is indistinguishable from a healthy server")
                .isNotEmpty();

        RecordedEvent first = faults.getFirst();
        assertThat(first.getString("faultClass"))
                .isEqualTo(ThrowingCryptoProvider.Boom.class.getName());
        assertThat(first.getString("bindAddress")).isEqualTo("127.0.0.1");
        assertThat(first.getLong("totalFaults")).isPositive();

        assertThat(faults)
                .as("the loop must recover — a per-connection failure that stopped accepting would "
                        + "trade a silent drop for an outage")
                .hasSizeGreaterThan(1);

        assertThat(faults).noneSatisfy(event ->
                assertThat(event.toString()).contains(ThrowingCryptoProvider.SECRET_MESSAGE));
    }

    /** Polls the recording until at least two faults are present, or the bound elapses. */
    private static void awaitFaults(Path jfr, Recording recording) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SETTLE_TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            recording.dump(jfr);
            long seen = RecordingFile.readAllEvents(jfr).stream()
                    .filter(e -> ACCEPT_FAULT.equals(e.getEventType().getName()))
                    .count();
            if (seen > 1) {
                return;
            }
            Thread.onSpinWait();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    private static Socket connectQuietly(int port) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS);
        } catch (IOException e) {
            // The server tears the connection down during setup; either side may notice first.
        }
        return socket;
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            // Teardown.
        }
    }

    /**
     * Fails every per-connection TLS engine creation. The message is deliberately distinctive so the
     * test can assert it never reaches the event — the payload carries the exception class only.
     */
    private static final class ThrowingCryptoProvider implements KernelCryptoProvider {

        private static final String SECRET_MESSAGE = "tls-init-detail-that-must-not-be-recorded";

        @Override
        public TlsEngine createTlsEngine(CryptoProviderConfig config) {
            throw new Boom(SECRET_MESSAGE);
        }

        @Override
        public boolean supportsQuic() {
            return false;
        }

        @Override
        public String providerName() {
            return "TestThrowingCrypto";
        }

        /** Named so the assertion on {@code faultClass} is unambiguous. */
        private static final class Boom extends RuntimeException {
            private static final long serialVersionUID = 1L;

            Boom(String message) {
                super(message);
            }
        }
    }
}
