/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.transport;

import eu.exeris.kernel.spi.transport.StreamHandler;
import eu.exeris.kernel.spi.transport.TransportEngine;
import eu.exeris.kernel.spi.transport.TransportMode;
import eu.exeris.kernel.spi.transport.TransportStats;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * TCK: Abstract base for {@link TransportEngine} lifecycle verification.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@code start()} transitions engine to running state</li>
 *   <li>{@code stop()} transitions engine to stopped state</li>
 *   <li>{@code close()} is idempotent</li>
 *   <li>Idempotent start/stop cycle — restart is safe</li>
 *   <li>{@code mode()} correctly reports the configured {@link TransportMode}</li>
 *   <li>{@code engineName()} is non-blank</li>
 *   <li>{@code stats()} returns valid diagnostics snapshot</li>
 * </ul>
 *
 * @since 0.5.0
 */
public abstract class AbstractTransportEngineTck {

    /** Small enough to reach in a unit test; nothing about the mechanism is size-dependent. */
    private static final int CEILING = 2;
    private static final int OVER_CEILING = 2;
    private static final int CONNECT_TIMEOUT_MS = 2_000;
    private static final long REJECTION_TIMEOUT_SECONDS = 5L;
    private static final long DRAIN_TIMEOUT_SECONDS = 10L;
    private static final byte[] DRAIN_PAYLOAD = {'D', 'R', 'A', 'I', 'N', 'E', 'D', '!'};

    /**
     * Creates a fully initialised (but not yet started) {@link TransportEngine} in SERVER mode.
     * The engine MUST have a stream handler already registered.
     */
    protected abstract TransportEngine createEngine();

    /**
     * Creates an unstarted SERVER-mode engine whose concurrent-connection ceiling is
     * {@code maxConnections}, with a stream handler already registered.
     *
     * <p>Abstract rather than defaulted-to-skip. A binding that never implemented this would pass the
     * refusal contract below by not running it — which is exactly the shape of the defect the
     * contract exists to catch, since the Community driver counted its own accept-time refusals
     * nowhere and reported a healthy zero while turning every connection away.
     *
     * @param maxConnections the ceiling; small values are expected, so a binding must not silently
     *                       clamp it to something it finds more reasonable
     * @param port           the port to bind; supplied by the suite so it can drive clients at the
     *                       engine without needing a binding-specific way to read the bound address
     */
    protected abstract TransportEngine createEngineWithConnectionCeiling(int maxConnections, int port);

    /**
     * Creates an unstarted SERVER-mode engine bound to {@code port} whose stream handler is the one
     * supplied, rather than one the binding chooses.
     *
     * <p>The drain contract cannot be asserted from outside the handler: proving that {@code stop()}
     * lets an in-flight exchange finish requires holding one open across the call, which only the
     * suite's own handler can do.
     *
     * @param port    the port to bind
     * @param handler the handler the engine must invoke for each admitted stream
     */
    protected abstract TransportEngine createEngineWithHandler(int port, StreamHandler handler);

    /**
     * Returns the expected {@link TransportMode} of the engine created by {@link #createEngine()}.
     */
    protected TransportMode expectedMode() {
        return TransportMode.SERVER;
    }

    private TransportEngine engine;

    @BeforeEach
    final void setUpEngine() {
        engine = createEngine();
    }

    @AfterEach
    final void tearDownEngine() {
        engine.close();
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Nested
    @DisplayName("Lifecycle contract")
    class Lifecycle {

        @Test
        @DisplayName("start() → stop() → close() — happy path")
        void happyPathLifecycle() {
            assertThatCode(() -> engine.start()).doesNotThrowAnyException();
            assertThatCode(() -> engine.stop()).doesNotThrowAnyException();
            assertThatCode(() -> engine.close()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("close() is idempotent — calling twice does not throw")
        void closeIsIdempotent() {
            engine.start();
            engine.stop();
            engine.close();
            assertThatCode(() -> engine.close()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("close() on a running engine implicitly stops it")
        void closeImplicitlyStops() {
            engine.start();
            assertThatCode(() -> engine.close()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("start() → stop() → start() — idempotent restart is safe")
        void idempotentRestart() {
            engine.start();
            engine.stop();
            // ADR-011: restart must be safe — engine binds new socket
            assertThatCode(() -> engine.start()).doesNotThrowAnyException();
            engine.stop();
        }
    }

    // =========================================================================
    // Mode reporting
    // =========================================================================

    @Nested
    @DisplayName("TransportMode reporting")
    class ModeReporting {

        @Test
        @DisplayName("mode() returns the configured transport mode")
        void modeMatchesConfig() {
            assertThat(engine.mode())
                    .as("Engine must report its configured mode")
                    .isEqualTo(expectedMode());
        }
    }

    // =========================================================================
    // Diagnostics
    // =========================================================================

    @Nested
    @DisplayName("Diagnostics contract")
    class Diagnostics {

        @Test
        @DisplayName("engineName() is non-blank")
        void engineNameNonBlank() {
            assertThat(engine.engineName()).isNotNull().isNotBlank();
        }

        @Test
        @DisplayName("stats() returns non-null snapshot before start")
        void statsBeforeStart() {
            TransportStats stats = engine.stats();
            assertThat(stats).isNotNull();
        }

        @Test
        @DisplayName("stats() returns valid snapshot after start")
        void statsAfterStart() {
            engine.start();
            TransportStats stats = engine.stats();
            assertThat(stats).isNotNull();
            assertThat(stats.activeConnections()).isGreaterThanOrEqualTo(0);
            assertThat(stats.totalAccepted()).isGreaterThanOrEqualTo(0);
            engine.stop();
        }

        @Test
        @DisplayName("totalRejected counts a refusal at the connection ceiling")
        void totalRejectedCountsCeilingRefusal() throws Exception {
            int port = freePort();
            TransportEngine bounded = createEngineWithConnectionCeiling(CEILING, port);
            List<Socket> clients = new ArrayList<>();
            try {
                bounded.start();
                for (int i = 0; i < CEILING + OVER_CEILING; i++) {
                    clients.add(connectQuietly(port));
                }
                awaitRejection(bounded);
            } finally {
                clients.forEach(AbstractTransportEngineTck::closeQuietly);
                bounded.close();
            }
        }

        /**
         * A refusal is not a fault, and the two counters must not be the same counter.
         *
         * <p>Asserted from the side an operator reads rather than by injecting a defect, which no
         * provider-neutral fixture can do: drive the ceiling until refusals are counted, then require
         * that the fault count has not moved. A driver that folded the two together, or that counted
         * a refusal as a setup failure, fails here — and the distinction is the one that decides
         * whether the operator is looking at a capacity problem or a bug.
         */
        @Test
        @DisplayName("a refusal at the ceiling is not counted as an accept fault")
        void ceilingRefusalIsNotAnAcceptFault() throws Exception {
            int port = freePort();
            TransportEngine bounded = createEngineWithConnectionCeiling(CEILING, port);
            List<Socket> clients = new ArrayList<>();
            try {
                bounded.start();
                // Held open, not opened and dropped: a client that disconnects immediately frees the
                // slot it took, and the ceiling is then never actually reached.
                for (int i = 0; i < CEILING + OVER_CEILING; i++) {
                    clients.add(connectQuietly(port));
                }
                awaitRejection(bounded);

                assertThat(bounded.stats().acceptFaults())
                        .as("declining work at a configured ceiling is a policy decision, not a "
                                + "setup failure — an operator who cannot tell them apart cannot "
                                + "tell a capacity problem from a defect")
                        .isZero();
            } finally {
                clients.forEach(AbstractTransportEngineTck::closeQuietly);
                bounded.close();
            }
        }
    }

    /**
     * A refusal at the ceiling MUST reach {@code totalRejected}.
     *
     * <p>The field is what an operator consults when asking whether the server is turning work away,
     * so a driver that counts only one of its refusal paths reports a healthy zero during a total
     * outage — a value read as evidence the fault lies elsewhere. The contract is that
     * {@code totalRejected} reflects <em>every</em> path by which the engine declines work, not
     * whichever one a driver happened to wire up.
     */
    private static void awaitRejection(TransportEngine bounded) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(REJECTION_TIMEOUT_SECONDS);
        while (bounded.stats().totalRejected() == 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertThat(bounded.stats().totalRejected())
                .as("a refusal the engine does not count is a refusal an operator cannot see")
                .isPositive();
    }

    @Nested
    @DisplayName("stop() drains in-flight streams before tearing the transport down")
    class GracefulDrain {

        @Test
        @DisplayName("a stream mid-exchange when stop() is called still delivers its response")
        void inFlightStreamSurvivesStop() throws Exception {
            CountDownLatch handlerEntered = new CountDownLatch(1);
            CountDownLatch releaseHandler = new CountDownLatch(1);
            int port = freePort();

            TransportEngine draining = createEngineWithHandler(port, stream -> {
                handlerEntered.countDown();
                try {
                    // Hold the exchange open so stop() is guaranteed to land mid-flight. Without this
                    // the handler could finish first and the test would pass on timing, not contract.
                    if (!releaseHandler.await(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        return;
                    }
                    MemorySegment response = MemorySegment.ofArray(DRAIN_PAYLOAD);
                    stream.write(response, DRAIN_PAYLOAD.length);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    stream.close();
                }
            });
            draining.start();

            Socket client = connectQuietly(port);
            try {
                client.setSoTimeout((int) TimeUnit.SECONDS.toMillis(DRAIN_TIMEOUT_SECONDS));
                assertThat(handlerEntered.await(DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        .as("the handler must be running before stop() is called, or this asserts nothing")
                        .isTrue();

                Thread stopper = Thread.ofPlatform().start(draining::stop);
                releaseHandler.countDown();

                byte[] received = client.getInputStream().readNBytes(DRAIN_PAYLOAD.length);
                stopper.join(TimeUnit.SECONDS.toMillis(DRAIN_TIMEOUT_SECONDS));

                assertThat(received)
                        .as("stop() promises in-flight streams are drained before being forcefully "
                                + "closed. Severing the socket first and draining afterwards leaves the "
                                + "handler running against a dead descriptor: it completes, the peer "
                                + "gets nothing, and an idempotent client retries into a closed listener")
                        .containsExactly(DRAIN_PAYLOAD);
            } finally {
                releaseHandler.countDown();
                closeQuietly(client);
                draining.close();
            }
        }
    }

    private static int freePort() {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("unable to allocate a free TCP port", e);
        }
    }

    private static Socket connectQuietly(int port) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS);
        } catch (IOException e) {
            // A refused connection may surface here or on a later read; the assertion reads the
            // server's own counter rather than guessing which side observes it first.
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
}
