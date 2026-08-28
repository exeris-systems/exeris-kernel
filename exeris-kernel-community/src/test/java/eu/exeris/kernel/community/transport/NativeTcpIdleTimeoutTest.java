/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.transport.TransportConfig;
import eu.exeris.kernel.spi.transport.TransportEngine;
import eu.exeris.kernel.spi.transport.TransportMode;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code transport.idleTimeoutMillis} must reclaim a connection that moves no bytes.
 *
 * <p>Through v0.11 it reclaimed nothing. The key was read from configuration, validated against
 * {@code >= 0}, carried through {@code HttpConfig} into {@code TransportConfig}, and rendered by
 * {@code toString()} — and never compared to anything. An operator could set it, see it echoed
 * back, and keep every idle connection forever. A knob that is carried but not consumed is
 * strictly worse than a missing one: the missing knob is discoverable.
 *
 * <p>The timeout is 500 ms here so the sweep interval derived from it is 250 ms (its floor) and
 * the test is bounded in wall-clock. Nothing in the mechanism is duration-dependent.
 */
@DisplayName("NativeTcpCarrier — transport.idleTimeoutMillis actually reclaims idle connections")
class NativeTcpIdleTimeoutTest {

    private static final String IDLE_TIMEOUT = "eu.exeris.kernel.transport.CommunityConnectionIdleTimeout";

    private static final long IDLE_TIMEOUT_MILLIS = 500L;
    private static final long DISABLED = 0L;
    private static final int CONNECT_TIMEOUT_MS = 2_000;
    private static final long SETTLE_TIMEOUT_SECONDS = 10L;
    private static final long QUIET_WINDOW_MILLIS = 2_500L;
    private static final int READ_CHUNK_BYTES = 64;

    private static final MemoryAllocator ALLOCATOR =
            new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());

    @Nested
    @DisplayName("with a timeout configured")
    class Enforced {

        @Test
        @DisplayName("reclaims a connection that sends nothing, and says so in JFR")
        void reclaimsIdleConnection() throws Exception {
            AtomicReference<RecordedEvent> captured = new AtomicReference<>();
            CountDownLatch reclaimed = new CountDownLatch(1);
            TransportEngine engine = null;
            Socket socket = null;

            // Awaits the EVENT, not the socket going EOF. The peer-side close and the emit are
            // separate observations of one teardown, and the socket can report EOF first — so
            // spinning on the socket would assert reclamation happened while proving nothing
            // about the signal an operator would actually consult.
            try (RecordingStream stream = new RecordingStream()) {
                stream.enable(IDLE_TIMEOUT);
                stream.onEvent(IDLE_TIMEOUT, event -> {
                    if (captured.compareAndSet(null, event)) {
                        reclaimed.countDown();
                    }
                });
                stream.startAsync();

                int port = CommunityTransportTestHarness.nextFreePort();
                engine = startEngine(port, IDLE_TIMEOUT_MILLIS);
                socket = openQuietly(port);

                assertThat(reclaimed.await(SETTLE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        .as("an idle connection past the configured timeout must be reclaimed, "
                                + "and the reclamation must be observable")
                        .isTrue();
            } finally {
                closeQuietly(socket);
                if (engine != null) {
                    engine.stop();
                }
            }

            RecordedEvent event = captured.get();
            assertThat(event.getLong("configuredTimeoutMillis"))
                    .as("the event must carry the limit that fired, not a hardcoded one")
                    .isEqualTo(IDLE_TIMEOUT_MILLIS);
            assertThat(event.getLong("idleMillis"))
                    .as("a connection reclaimed before it was idle for the configured span would "
                            + "mean the sweep is judging something other than activity")
                    .isGreaterThanOrEqualTo(IDLE_TIMEOUT_MILLIS);
        }

        @Test
        @DisplayName("a connection that keeps reading is NOT reclaimed — the case that makes this a timeout and not a lifetime")
        void activeConnectionSurvivesPastTheTimeout() throws Exception {
            // The two cases above are both satisfied by a carrier that reclaims EVERY connection
            // on a 500 ms timer: one asserts a reclamation happens, the other only that a disabled
            // reaper does nothing. Neither one asks whether the sweep looks at activity at all.
            // This one does, and it is the case that fails if the activity stamp is removed.
            AtomicReference<RecordedEvent> captured = new AtomicReference<>();
            TransportEngine engine = null;
            Socket socket = null;

            try (RecordingStream stream = new RecordingStream()) {
                stream.enable(IDLE_TIMEOUT);
                stream.onEvent(IDLE_TIMEOUT, event -> captured.compareAndSet(null, event));
                stream.startAsync();

                int port = CommunityTransportTestHarness.nextFreePort();
                engine = startEchoingEngine(port, IDLE_TIMEOUT_MILLIS);
                socket = openQuietly(port);

                // Five times the timeout, fed at a third of it: every sweep in the window sees a
                // stamp younger than the limit.
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(QUIET_WINDOW_MILLIS);
                boolean resetByPeer = false;
                while (System.nanoTime() < deadline) {
                    try {
                        socket.getOutputStream().write('.');
                        socket.getOutputStream().flush();
                    } catch (IOException reset) {
                        // A reclaimed connection surfaces here as a reset on the next write. Catch
                        // it so the failure reads as the assertion below rather than as a stack
                        // trace from the send loop — the regression this guards is a policy
                        // decision, and it should be reported as one.
                        resetByPeer = true;
                        break;
                    }
                    Thread.sleep(IDLE_TIMEOUT_MILLIS / 3L);
                }

                assertThat(resetByPeer)
                        .as("the server reset a connection that was reading every %d ms under a "
                                + "%d ms timeout", IDLE_TIMEOUT_MILLIS / 3L, IDLE_TIMEOUT_MILLIS)
                        .isFalse();
            } finally {
                closeQuietly(socket);
                if (engine != null) {
                    engine.stop();
                }
            }

            assertThat(captured.get())
                    .as("a connection reading every 166 ms under a 500 ms timeout is the opposite "
                            + "of idle; reclaiming it would make the knob a connection lifetime")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("with the timeout disabled")
    class Disabled {

        @Test
        @DisplayName("0 leaves an idle connection alone — the control that makes the other case mean something")
        void zeroDisablesReclamation() throws Exception {
            // Without this case, a green reclamation test proves only that the connection died,
            // not that the timeout killed it: a carrier that dropped every idle connection
            // unconditionally would pass the first test identically. The quiet window is five
            // times the timeout used above, so a reaper wrongly enabled here has had four sweeps
            // to fire.
            AtomicReference<RecordedEvent> captured = new AtomicReference<>();
            TransportEngine engine = null;
            Socket socket = null;

            try (RecordingStream stream = new RecordingStream()) {
                stream.enable(IDLE_TIMEOUT);
                stream.onEvent(IDLE_TIMEOUT, event -> captured.compareAndSet(null, event));
                stream.startAsync();

                int port = CommunityTransportTestHarness.nextFreePort();
                engine = startEngine(port, DISABLED);
                socket = openQuietly(port);

                Thread.sleep(QUIET_WINDOW_MILLIS);
            } finally {
                closeQuietly(socket);
                if (engine != null) {
                    engine.stop();
                }
            }

            // The absent event is the whole assertion, deliberately. Socket.isClosed() reports only
            // the LOCAL close state — it stays false after a peer reset — so asserting on it here
            // would read as proof while being incapable of failing.
            assertThat(captured.get())
                    .as("idleTimeoutMillis=0 is documented as 'no timeout'; a reclamation here "
                            + "would make that contract false")
                    .isNull();
        }
    }

    @Nested
    @DisplayName("reaper construction")
    class ReaperConstruction {

        @Test
        @DisplayName("0 and negative build a disabled reaper; a positive timeout is carried in nanos")
        void disableSemantics() {
            assertThat(NativeTcpIdleReaper.forTimeout(0L).enabled()).isFalse();
            assertThat(NativeTcpIdleReaper.forTimeout(-1L).enabled()).isFalse();

            NativeTcpIdleReaper reaper = NativeTcpIdleReaper.forTimeout(IDLE_TIMEOUT_MILLIS);
            assertThat(reaper.enabled()).isTrue();
            assertThat(reaper.idleTimeoutNanos())
                    .isEqualTo(TimeUnit.MILLISECONDS.toNanos(IDLE_TIMEOUT_MILLIS));
        }
    }

    private static TransportEngine startEngine(int port, long idleTimeoutMillis) {
        TransportEngine[] holder = new TransportEngine[1];
        ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, ALLOCATOR).run(() -> {
            holder[0] = new NativeTcpTransportProvider().createEngine(new TransportConfig(
                    TransportMode.SERVER, "127.0.0.1", port, 1, null, null, 16, idleTimeoutMillis));
            // The handler must never read: a read stamps activity, which is the very thing the
            // timeout measures the absence of.
            holder[0].setStreamHandler(stream -> { });
            holder[0].start();
        });
        return holder[0];
    }

    private static TransportEngine startEchoingEngine(int port, long idleTimeoutMillis) {
        TransportEngine[] holder = new TransportEngine[1];
        ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, ALLOCATOR).run(() -> {
            holder[0] = new NativeTcpTransportProvider().createEngine(new TransportConfig(
                    TransportMode.SERVER, "127.0.0.1", port, 1, null, null, 16, idleTimeoutMillis));
            holder[0].setStreamHandler(stream -> {
                try (LoanedBuffer inbound = ALLOCATOR.allocateNetwork(READ_CHUNK_BYTES)) {
                    while (stream.read(inbound.segment(), READ_CHUNK_BYTES) >= 0) {
                        // Each returning read stamps activity; the loop is the connection staying
                        // alive. It ends when the peer closes (-1) or teardown throws.
                    }
                } catch (RuntimeException e) {
                    // Reclaimed or torn down — the assertions read the recording, not this thread.
                }
            });
            holder[0].start();
        });
        return holder[0];
    }

    private static Socket openQuietly(int port) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT_MS);
        } catch (IOException e) {
            // The server side carries every assertion; connect-side failure modes are not the subject.
        }
        return socket;
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException e) {
            // Test teardown.
        }
    }
}
