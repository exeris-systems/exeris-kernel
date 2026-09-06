/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.http;

import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.http.StreamClosedException;
import eu.exeris.kernel.spi.http.HttpStreamExchange;
import eu.exeris.kernel.spi.http.HttpStreamHandler;
import eu.exeris.kernel.spi.http.StreamEvent;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCK: Abstract base for the server-push streaming contract — {@link HttpStreamExchange},
 * {@link HttpStreamHandler}, {@link StreamEvent} (ADR-043).
 *
 * <h2>Why a loopback harness, not a fixture exchange</h2>
 * <p>Unlike {@link AbstractHttpExchangeTck} (which can construct a fixture exchange and assert
 * respond-once), the streaming contract is only observable over a <em>real held-open transport
 * round-trip</em>: backpressure-park, peer-disconnect, graceful end-of-stream and fail-closed teardown
 * are all transport-edge behaviours. This TCK therefore mirrors {@link AbstractHttpProviderLoopbackTck}:
 * the binding starts a streaming endpoint with a supplied {@link HttpStreamHandler}, connects a client,
 * and reports observations back through a {@link StreamScenario} handle. The TCK module depends on
 * {@code exeris-kernel-spi} only (The Wall); the SSE wire framing (Core {@code SseEventEncoder}) and the
 * held-open transport mechanics (Community NIO / Enterprise native) live behind the binding's hooks.
 *
 * <h2>Contract pinned (ADR-043 Engineering Protocol §1)</h2>
 * <ul>
 *   <li><b>open → emit-N → graceful close.</b> The client observes N well-formed {@link StreamEvent}s
 *       in order, then a clean end-of-stream.</li>
 *   <li><b>Disconnect = the next {@code emit()} throws.</b> After the client disconnects, the handler's
 *       next {@link HttpStreamExchange#emit(StreamEvent)} throws {@link StreamClosedException} carrying
 *       {@link KernelErrorCodes#EX_HTTP_4011}.</li>
 *   <li><b>{@link eu.exeris.kernel.spi.memory.LoanedBuffer} zero-leak</b> across the full stream
 *       lifecycle (open, emit-N, close, and the disconnect-unwind path).</li>
 *   <li><b>Backpressure park-and-resume.</b> When the client stops reading, the emitting virtual thread
 *       PARKS inside {@code emit()} (no on-heap queue); when the client drains / window credit returns it
 *       RESUMES. This is the most regression-prone path across tiers.</li>
 *   <li><b>JWT-expiry mid-stream</b> closes the stream with {@link KernelErrorCodes#EX_HTTP_4012}
 *       (fail-closed per ADR-012 §5, never a silent drop).</li>
 *   <li><b>Stream-open shed</b> reuses {@link KernelErrorCodes#EX_NET_4006} + the existing
 *       {@code StreamShedEvent} (no new HTTP shed code); already-open streams keep emitting.</li>
 *   <li><b>Respond-once regression guard.</b> A streaming route resolves to {@link HttpStreamHandler}
 *       and never delivers a respond-once {@code HttpExchange}; the respond-once invariant is untouched.</li>
 * </ul>
 *
 * <h2>How to bind</h2>
 * {@snippet lang="java" :
 * class CommunityHttpStreamExchangeTckTest extends AbstractHttpStreamExchangeTck {
 *     @Override
 *     protected StreamScenario openStream(HttpStreamHandler handler) {
 *         return CommunityStreamLoopback.start(handler); // NIO server + EventSource-style client
 *     }
 *     // override the capability hooks the Community tier actually wires (backpressure, auth, shed)
 * }
 * }
 *
 * @since 0.10
 */
public abstract class AbstractHttpStreamExchangeTck {

    /** Default well-formed event count for the happy-path emit-N case. */
    protected static final int DEFAULT_EVENT_COUNT = 3;

    /** Bound on how long a park/resume or disconnect observation may take before the test fails. */
    private static final long OBSERVE_TIMEOUT_SECONDS = 10L;

    /**
     * Longer guard for the two integration-style probes (backpressure drain, auth-expiry fail-closed).
     * These run in a dedicated forked Surefire execution and genuinely complete eventually; under full
     * machine load a correct park/resume or fail-closed teardown may take much longer than the tight
     * {@link #OBSERVE_TIMEOUT_SECONDS} bound the fast mandatory cases keep. This bound only guards
     * against a true hang, never against slowness — it does not weaken what either probe proves.
     */
    private static final long SLOW_PROBE_TIMEOUT_SECONDS = 30L;

    /**
     * Flood size for the backpressure probe. The park condition only trips once the kernel refuses a
     * socket write: egress credit returns when the transport accepts a frame (buffer close), not when
     * the client reads it. The flood must therefore dominate everything the OS can absorb with a
     * stalled reader — server {@code SO_SNDBUF} + client {@code SO_RCVBUF} (Linux doubles both
     * setsockopt values) — plus the credit window itself, with margin for the pre-stall drain race.
     * 2_000 (~23 KB of {@code data: N} frames) sat below that ceiling (~36 KB with the reference
     * binding's 8 KB/2 KB sockets and 16 KB window), so emit() could legitimately complete without
     * ever parking on some boxes; 8_000 (~95 KB) clears it several times over while still draining
     * well within {@link #SLOW_PROBE_TIMEOUT_SECONDS} on a loaded box.
     */
    protected static final int BACKPRESSURE_FLOOD = 8_000;

    // -------------------------------------------------------------------------
    // Mandatory binding hook — the real loopback round-trip
    // -------------------------------------------------------------------------

    /**
     * Starts a streaming endpoint bound to {@code handler}, connects a client, and returns a live
     * {@link StreamScenario} the TCK drives. The binding owns the held-open transport (NIO / native),
     * the SSE wire framing, and the client that parses the wire back into {@link StreamEvent}s.
     *
     * <p>The handler runs on its own virtual thread (1 VT per stream). The returned scenario MUST be
     * usable immediately: the response head (200, {@code text/event-stream}) is already written and the
     * handler is being invoked.
     *
     * @param handler the application emit-loop to drive; never {@code null}
     * @return a live scenario handle; never {@code null}
     */
    protected abstract StreamScenario openStream(HttpStreamHandler handler);

    // -------------------------------------------------------------------------
    // Optional capability hooks — declared here, gated by JUnit assumptions.
    // A binding that does not yet wire a capability is reported SKIPPED, never
    // silently green (mirrors AbstractTransportStreamTck#expectsTrueReset).
    // -------------------------------------------------------------------------

    /**
     * Whether this binding can deterministically PARK the emitting virtual thread by stalling the client
     * read side, then RESUME it on drain (see {@link StreamScenario#stallClient()} /
     * {@link StreamScenario#drainClient()}). Backpressure-park is the path ADR-043 flags as most
     * regression-prone across tiers; a binding that cannot stall the client deterministically is reported
     * SKIPPED rather than passing a park assertion it does not actually exercise.
     *
     * @return {@code true} if the binding wires deterministic stall/drain; {@code false} by default
     */
    protected boolean supportsBackpressureProbe() {
        return false;
    }

    /**
     * Whether this binding can simulate JWT expiry mid-stream (ADR-043 obligation 6). Until the
     * IdentityProvider SPI (ADR-040) lands, the {@code exp} deadline is derived inside the Community JWT
     * path, so this is binding-internal wiring. A binding without it is reported SKIPPED.
     *
     * @return {@code true} if the binding can force a mid-stream expiry; {@code false} by default
     */
    protected boolean supportsAuthExpiryProbe() {
        return false;
    }

    /**
     * Whether this binding can force the PAQS shed decision so a NEW stream-open is rejected
     * ({@link KernelErrorCodes#EX_NET_4006} + {@code StreamShedEvent}; ADR-043 obligation 7). A binding
     * without a shed hook is reported SKIPPED.
     *
     * @return {@code true} if the binding can force a shed decision; {@code false} by default
     */
    protected boolean supportsShedProbe() {
        return false;
    }

    /**
     * Opens a stream whose authenticated principal expires almost immediately, so the engine's deadline
     * fires while the handler is still emitting. Only consulted when {@link #supportsAuthExpiryProbe()}
     * is {@code true}; the default throws to make an un-wired override visible.
     *
     * @param handler the emit-loop to drive against the about-to-expire principal; never {@code null}
     * @return a live scenario whose stream will be fail-closed on expiry; never {@code null}
     */
    protected StreamScenario openExpiringStream(HttpStreamHandler handler) {
        throw new UnsupportedOperationException("openExpiringStream not wired by this binding");
    }

    /**
     * Attempts a NEW stream-open while the PAQS arbiter is forced to {@code SHED_LOAD}, returning the
     * exception the open was rejected with. Only consulted when {@link #supportsShedProbe()} is
     * {@code true}; the default throws to make an un-wired override visible.
     *
     * @param handler the handler that would have run had the open been admitted; never {@code null}
     * @return the rejection thrown by the shed path; never {@code null}
     */
    protected ExerisKernelException openUnderShed(HttpStreamHandler handler) {
        throw new UnsupportedOperationException("openUnderShed not wired by this binding");
    }

    // -------------------------------------------------------------------------
    // Path parameters
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Path parameters")
    class PathParameters {

        @Test
        @Timeout(value = OBSERVE_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
        @DisplayName("an exchange not opened through a template reports an empty, immutable map")
        void defaultIsEmptyAndImmutable() {
            AtomicReference<Map<String, String>> seen = new AtomicReference<>();
            AtomicReference<Boolean> mutable = new AtomicReference<>(Boolean.TRUE);

            StreamScenario scenario = openStream(exchange -> {
                seen.set(exchange.pathParams());
                try {
                    exchange.pathParams().put("injected", "value");
                } catch (RuntimeException _) {
                    mutable.set(Boolean.FALSE);
                }
                exchange.emit(StreamEvent.of("ready"));
                exchange.close();
            });

            try (scenario) {
                scenario.awaitEvents(1);
                assertThat(seen.get())
                        .as("never null — a handler reading pathParams() on an exact route must not "
                                + "have to null-check what the contract promises")
                        .isNotNull()
                        .isEmpty();
                assertThat(mutable.get())
                        .as("the map is routing state; a handler that could write to it could change "
                                + "what a later request resolves to")
                        .isFalse();
            }
        }
    }

    // -------------------------------------------------------------------------
    // open → emit-N → graceful close
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("open → emit-N → graceful close")
    class GracefulLifecycle {

        @Test
        @Timeout(value = OBSERVE_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
        @DisplayName("client observes N well-formed events in order, then end-of-stream")
        void emitsNEventsThenCloses() {
            StreamScenario scenario = openStream(exchange -> {
                for (int idx = 0; idx < DEFAULT_EVENT_COUNT; idx++) {
                    exchange.emit(StreamEvent.of("tick", Integer.toString(idx)));
                }
                exchange.close();
            });

            try (scenario) {
                List<StreamEvent> observed = scenario.awaitEvents(DEFAULT_EVENT_COUNT);
                assertThat(observed)
                        .as("client must observe exactly the N emitted events")
                        .hasSize(DEFAULT_EVENT_COUNT);
                for (int idx = 0; idx < DEFAULT_EVENT_COUNT; idx++) {
                    assertThat(observed.get(idx).event())
                            .as("event #%d type must round-trip", idx).isEqualTo("tick");
                    assertThat(observed.get(idx).data())
                            .as("event #%d data must round-trip in order", idx)
                            .isEqualTo(Integer.toString(idx));
                }
                assertThat(scenario.awaitEndOfStream())
                        .as("graceful close() must surface a clean end-of-stream to the client")
                        .isTrue();
            }
        }

        @Test
        @Timeout(value = OBSERVE_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
        @DisplayName("close() is idempotent — handler may close twice without throwing")
        void closeIsIdempotent() {
            StreamScenario scenario = openStream(exchange -> {
                exchange.emit(StreamEvent.of("only"));
                exchange.close();
                exchange.close();
            });
            try (scenario) {
                assertThat(scenario.awaitEndOfStream())
                        .as("a second close() must be a no-op, not an error")
                        .isTrue();
                assertThat(scenario.handlerError())
                        .as("idempotent close() must not surface any handler error")
                        .isNull();
            }
        }
    }

    // -------------------------------------------------------------------------
    // disconnect → next emit() throws StreamClosedException (EX-HTTP-4011)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("disconnect → next emit() throws EX-HTTP-4011")
    class DisconnectUnwind {

        @Test
        @Timeout(value = OBSERVE_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
        @DisplayName("after client disconnect, the next emit() throws StreamClosedException(EX-HTTP-4011)")
        void emitAfterDisconnectThrows() {
            AtomicReference<Throwable> captured = new AtomicReference<>();
            CountDownLatch disconnected = new CountDownLatch(1);
            StreamScenario scenario = openStream(exchange -> {
                try {
                    // First emit lands; then the client goes away and the next emit must throw.
                    exchange.emit(StreamEvent.of("before-disconnect"));
                    // CountDownLatch.await unmounts the virtual thread; it does NOT pin the carrier the
                    // way synchronized/Object.wait would — load-bearing under the 2-carrier CI model.
                    disconnected.await(OBSERVE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    while (true) {
                        exchange.emit(StreamEvent.of("after-disconnect"));
                    }
                } catch (StreamClosedException ex) {
                    captured.set(ex);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            });

            try (scenario) {
                scenario.awaitEvents(1);
                scenario.disconnectClient();
                disconnected.countDown();

                Throwable thrown = scenario.awaitHandlerUnwind();
                assertThat(thrown)
                        .as("the emit loop must unwind via StreamClosedException on disconnect")
                        .isInstanceOf(StreamClosedException.class);
                assertThat(captured.get())
                        .as("the throw observed inside the handler must be the same disconnect signal")
                        .isInstanceOf(StreamClosedException.class);
                StreamClosedException closed = (StreamClosedException) thrown;
                assertThat(closed.errorCode())
                        .as("emit-after-close MUST carry EX-HTTP-4011 (ADR-043 obligation 3 / 8)")
                        .isEqualTo(KernelErrorCodes.EX_HTTP_4011);
            }
        }
    }

    // -------------------------------------------------------------------------
    // LoanedBuffer zero-leak across the full lifecycle
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("LoanedBuffer zero-leak across the stream lifecycle")
    class BufferAccounting {

        @Test
        @Timeout(value = OBSERVE_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
        @DisplayName("graceful open→emit-N→close leaves zero outstanding loans")
        void gracefulPathLeaksNoBuffers() {
            StreamScenario scenario = openStream(exchange -> {
                for (int idx = 0; idx < DEFAULT_EVENT_COUNT; idx++) {
                    exchange.emit(StreamEvent.of(Integer.toString(idx)));
                }
                exchange.close();
            });
            long outstanding;
            try (scenario) {
                scenario.awaitEvents(DEFAULT_EVENT_COUNT);
                scenario.awaitEndOfStream();
                outstanding = scenario.outstandingLoans();
            }
            assertThat(outstanding)
                    .as("every emit transfers its LoanedBuffer to the engine; none may leak after close")
                    .isZero();
        }

        @Test
        @Timeout(value = OBSERVE_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
        @DisplayName("the disconnect-unwind path also leaves zero outstanding loans")
        void disconnectPathLeaksNoBuffers() {
            StreamScenario scenario = openStream(exchange -> {
                try {
                    while (true) {
                        exchange.emit(StreamEvent.of("x"));
                    }
                } catch (StreamClosedException ignored) {
                    // expected unwind
                }
            });
            long outstanding;
            try (scenario) {
                scenario.awaitEvents(1);
                scenario.disconnectClient();
                scenario.awaitHandlerUnwind();
                outstanding = scenario.outstandingLoans();
            }
            assertThat(outstanding)
                    .as("abortive teardown (#202 path) must release the in-flight egress buffer too")
                    .isZero();
        }
    }

    // -------------------------------------------------------------------------
    // backpressure park-and-resume  (gated)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("backpressure: emit() parks the VT and resumes on window credit")
    class Backpressure {

        @Test
        @Timeout(value = SLOW_PROBE_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
        @DisplayName("emit() parks while the client is stalled, resumes when it drains")
        void emitParksThenResumesOnCredit() {
            Assumptions.assumeTrue(supportsBackpressureProbe(),
                    "binding does not wire a deterministic client-stall backpressure probe");

            // Emit enough to overflow the binding's tiny egress window several times over, forcing the
            // emitting VT to park repeatedly — but not so much that the probe becomes throughput-bound.
            int flood = BACKPRESSURE_FLOOD;
            StreamScenario scenario = openStream(exchange -> {
                for (int idx = 0; idx < flood; idx++) {
                    exchange.emit(StreamEvent.of(Integer.toString(idx)));
                }
                exchange.close();
            });

            try (scenario) {
                scenario.stallClient();
                // Once the window fills, the emitting VT must be PARKED inside emit() — not spinning
                // and not buffering to heap. The binding observes its own VT state.
                assertThat(scenario.awaitEmitterParked())
                        .as("emit() MUST park the emitting virtual thread under backpressure "
                                + "(No Waste Compute — never an unbounded heap queue)")
                        .isTrue();

                scenario.drainClient();
                List<StreamEvent> observed = scenario.awaitEvents(flood);
                assertThat(observed)
                        .as("after the client drains, the parked emit() MUST resume and deliver all events")
                        .hasSize(flood);
                assertThat(scenario.awaitEndOfStream()).isTrue();
                assertThat(scenario.outstandingLoans())
                        .as("park/resume must not leak the in-flight egress buffer")
                        .isZero();
            }
        }
    }

    // -------------------------------------------------------------------------
    // JWT-expiry mid-stream → EX-HTTP-4012 (fail-closed)  (gated)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("JWT expiry mid-stream → fail-closed EX-HTTP-4012")
    class AuthExpiry {

        @Test
        @Timeout(value = SLOW_PROBE_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
        @DisplayName("a held-open stream is deterministically closed with EX-HTTP-4012 when the token expires")
        void expiryClosesStreamFailClosed() {
            Assumptions.assumeTrue(supportsAuthExpiryProbe(),
                    "binding does not wire a JWT-expiry probe (ADR-040 IdentityProvider SPI pending)");

            StreamScenario scenario = openExpiringStream(exchange -> {
                try {
                    while (true) {
                        exchange.emit(StreamEvent.of("live"));
                    }
                } catch (StreamClosedException ignored) {
                    // expected fail-closed unwind
                }
            });

            try (scenario) {
                Throwable thrown = scenario.awaitHandlerUnwind();
                assertThat(thrown)
                        .as("token expiry MUST fail-closed via StreamClosedException, never a silent drop")
                        .isInstanceOf(StreamClosedException.class);
                assertThat(((StreamClosedException) thrown).errorCode())
                        .as("mid-stream expiry MUST carry EX-HTTP-4012 (ADR-012 §5 fail-closed)")
                        .isEqualTo(KernelErrorCodes.EX_HTTP_4012);
                assertThat(scenario.outstandingLoans())
                        .as("fail-closed teardown must not leak buffers either")
                        .isZero();
            }
        }
    }

    // -------------------------------------------------------------------------
    // stream-open shed → EX-NET-4006 (reuse), already-open streams keep emitting  (gated)
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("stream-open shed reuses EX-NET-4006 + StreamShedEvent")
    class OpenShed {

        @Test
        @Timeout(value = OBSERVE_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
        @DisplayName("a NEW stream-open under SHED_LOAD is rejected with the existing EX-NET-4006 (no new code)")
        void newOpenUnderShedReusesNetCode() {
            Assumptions.assumeTrue(supportsShedProbe(),
                    "binding does not wire a PAQS shed probe");

            ExerisKernelException rejection = openUnderShed(exchange -> exchange.close());
            assertThat(rejection)
                    .as("a shed stream-open must surface a structured kernel rejection")
                    .isNotNull();
            assertThat(rejection.errorCode())
                    .as("stream-open shed MUST reuse the existing PAQS code EX-NET-4006 — "
                            + "ADR-043 mints NO new HTTP shed code")
                    .isEqualTo(KernelErrorCodes.EX_NET_4006);
        }

        @Test
        @Timeout(value = OBSERVE_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
        @DisplayName("already-open streams keep emitting while new opens are shed")
        void alreadyOpenStreamsSurviveShed() {
            Assumptions.assumeTrue(supportsShedProbe(),
                    "binding does not wire a PAQS shed probe");

            StreamScenario alreadyOpen = openStream(exchange -> {
                for (int idx = 0; idx < DEFAULT_EVENT_COUNT; idx++) {
                    exchange.emit(StreamEvent.of(Integer.toString(idx)));
                }
                exchange.close();
            });
            try (alreadyOpen) {
                ExerisKernelException rejection = openUnderShed(exchange -> exchange.close());
                assertThat(rejection.errorCode()).isEqualTo(KernelErrorCodes.EX_NET_4006);
                assertThat(alreadyOpen.awaitEvents(DEFAULT_EVENT_COUNT))
                        .as("ADR-043 obligation 7: shed rejects NEW opens; live streams continue")
                        .hasSize(DEFAULT_EVENT_COUNT);
                assertThat(alreadyOpen.awaitEndOfStream()).isTrue();
            }
        }
    }

    // -------------------------------------------------------------------------
    // respond-once regression guard
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("respond-once invariant is untouched")
    class RespondOnceGuard {

        @Test
        @Timeout(value = OBSERVE_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
        @DisplayName("a streaming route resolves to HttpStreamHandler, never delivering a respond-once exchange")
        void streamingRouteNeverDeliversRespondOnce() {
            AtomicReference<HttpStreamExchange> delivered = new AtomicReference<>();
            StreamScenario scenario = openStream(exchange -> {
                delivered.set(exchange);
                exchange.emit(StreamEvent.of("hello"));
                exchange.close();
            });
            try (scenario) {
                scenario.awaitEvents(1);
                scenario.awaitEndOfStream();
                assertThat(delivered.get())
                        .as("a streaming route MUST resolve to HttpStreamExchange, never a respond-once exchange")
                        .isInstanceOf(HttpStreamExchange.class);
            }
        }

        @Test
        @DisplayName("emit on an already-closed exchange throws EX-HTTP-4011 (handler-local, no transport)")
        void emitAfterCloseThrowsLocally() {
            // Purely SPI-level: the StreamClosedException error-code taxonomy holds regardless of
            // transport. The peerDisconnect() factory is the canonical emit-after-close signal.
            StreamClosedException afterClose = StreamClosedException.peerDisconnect(DEFAULT_EVENT_COUNT);
            assertThat(afterClose.errorCode()).isEqualTo(KernelErrorCodes.EX_HTTP_4011);
            assertThatThrownBy(() -> { throw afterClose; })
                    .isInstanceOf(StreamClosedException.class)
                    .isInstanceOf(RuntimeException.class);
        }
    }


    /**
     * Binding-supplied handle to one live streaming round-trip. The binding owns the held-open transport,
     * the SSE framing, the client that parses the wire back into {@link StreamEvent}s, and the loan
     * accounting. The TCK only observes SPI-shaped facts through this interface (The Wall).
     *
     * <p>{@link #close()} tears the scenario down (stops the endpoint, closes the client, frees the
     * allocator) and is invoked via try-with-resources from every test.
     *
     * @since 0.10
     */
    protected interface StreamScenario extends AutoCloseable {

        /**
         * Blocks until the client has parsed at least {@code count} events, then returns them in order.
         *
         * @param count number of events to await
         * @return the observed events, in wire order; never {@code null}
         */
        List<StreamEvent> awaitEvents(int count);

        /**
         * Blocks until the client observes a clean end-of-stream (graceful {@code close()} FIN).
         *
         * @return {@code true} if a clean end-of-stream was observed
         */
        boolean awaitEndOfStream();

        /**
         * Abruptly disconnects the client (drops the connection) so the next handler {@code emit()} throws.
         */
        void disconnectClient();

        /**
         * Blocks until the handler emit-loop has unwound and returns the {@link Throwable} that ended it
         * (the {@link StreamClosedException} on disconnect / fail-closed paths), or {@code null} if it
         * returned normally.
         *
         * @return the throwable that unwound the handler, or {@code null}
         */
        Throwable awaitHandlerUnwind();

        /**
         * Returns the error the handler ended with, or {@code null} if it completed normally. Distinct
         * from {@link #awaitHandlerUnwind()} in that it does not block beyond a completed run.
         *
         * @return the handler's terminal throwable, or {@code null}
         */
        Throwable handlerError();

        /**
         * Returns the number of {@link eu.exeris.kernel.spi.memory.LoanedBuffer}s still outstanding
         * (not released to the pool) for this stream. MUST be {@code 0} after the stream has fully torn
         * down on any path (graceful close, disconnect, fail-closed expiry).
         *
         * @return outstanding loan count; {@code 0} when leak-free
         */
        long outstandingLoans();

        /**
         * Stalls the client read side so the egress window fills and the emitting VT parks. Only called
         * when {@link #supportsBackpressureProbe()} is {@code true}.
         */
        default void stallClient() {
            throw new UnsupportedOperationException("stallClient not wired by this binding");
        }

        /**
         * Resumes the stalled client so window credit returns and the parked {@code emit()} continues.
         * Only called when {@link #supportsBackpressureProbe()} is {@code true}.
         */
        default void drainClient() {
            throw new UnsupportedOperationException("drainClient not wired by this binding");
        }

        /**
         * Blocks until the emitting virtual thread is observed PARKED inside {@code emit()} (waiting on
         * window credit), returning {@code true} once parked. Only called when
         * {@link #supportsBackpressureProbe()} is {@code true}.
         *
         * @return {@code true} once the emitting VT is parked under backpressure
         */
        default boolean awaitEmitterParked() {
            throw new UnsupportedOperationException("awaitEmitterParked not wired by this binding");
        }

        @Override
        void close();
    }
}
