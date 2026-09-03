/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.websocket;

import eu.exeris.kernel.spi.exceptions.http.WebSocketClosedException;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.websocket.WebSocketCloseCode;
import eu.exeris.kernel.spi.websocket.WebSocketConfig;
import eu.exeris.kernel.spi.websocket.WebSocketExchange;
import eu.exeris.kernel.spi.websocket.WebSocketHandler;
import eu.exeris.kernel.spi.websocket.WebSocketHandshake;
import eu.exeris.kernel.spi.websocket.WebSocketHandshakeHandler;
import eu.exeris.kernel.spi.websocket.WebSocketSession;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCK for the duplex exchange contract (ADR-084).
 *
 * <p>Bindings drive a real connection: the binding owns the transport, the RFC 6455 framing and the
 * client, and this class owns the contract. Shaped after {@code AbstractHttpStreamExchangeTck},
 * including its rule that an unwired capability is reported SKIPPED rather than passing an assertion
 * it never exercised.
 *
 * <h2>What this TCK is deliberately not</h2>
 * <p>It is <strong>not the promotion gate</strong>. A contract test proves a shape is honoured, not
 * that it survives — it opens a connection, exchanges messages and closes, and says nothing about a
 * thousand of them, a reader that stops reading, or a peer that dies without a close frame. ADR-084
 * §10 gates `stable` on benchmark evidence for exactly that reason, and a binding that goes green
 * here has satisfied the contract, not the promotion criteria.
 *
 * <h2>The handshake is pinned in both directions</h2>
 * <p>A test that only proves acceptance passes against an engine that accepts everything, which is
 * the failure ADR-084 §6 exists to prevent. Refusal is asserted first-class: an unlisted origin must
 * be refused <em>with no handshake callback written at all</em>.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractWebSocketExchangeTck {

    /** How long a contract assertion waits on the wire before it is a failure rather than slowness. */
    protected static final int WIRE_TIMEOUT_SECONDS = 10;

    /** An origin the tests configure as permitted. */
    protected static final String ALLOWED_ORIGIN = "https://studio.example";

    /** An origin no test ever configures, used to assert the refusal path. */
    protected static final String UNLISTED_ORIGIN = "https://evil.example";

    // -------------------------------------------------------------------------
    // Mandatory binding hook
    // -------------------------------------------------------------------------

    /**
     * Starts an endpoint on {@code config}, wires {@code handler} and {@code handshakeHandler},
     * connects a client from {@code clientOrigin}, and returns a live scenario.
     *
     * <p>The scenario MUST be usable immediately on return when the handshake succeeded. When it was
     * refused, {@link WebSocketScenario#handshakeStatus()} carries the status the client saw and every
     * message operation is undefined — the tests do not call them on a refused scenario.
     *
     * @param config           the endpoint configuration the test needs
     * @param handler          the connection handler; never {@code null}
     * @param handshakeHandler the handshake callback, or {@code null} for none — which is the case
     *                         that proves the allowlist refuses on its own
     * @param clientOrigin     the {@code Origin} the client presents
     * @return a scenario handle; never {@code null}
     */
    protected abstract WebSocketScenario connect(WebSocketConfig config,
                                                 WebSocketHandler handler,
                                                 WebSocketHandshakeHandler handshakeHandler,
                                                 String clientOrigin);

    /**
     * Whether the binding's client can send a message split across continuation frames.
     *
     * <p>Gated rather than assumed: reassembly is a contract this TCK asserts, and a binding whose
     * client cannot fragment would otherwise report green on a path it never drove.
     *
     * @return {@code true} if {@link WebSocketScenario#sendFragmentedFromClient(String, int)} works
     */
    protected boolean supportsClientFragmentation() {
        return false;
    }

    /**
     * Whether the binding's client can send a binary frame, which a text-only contract must refuse.
     *
     * @return {@code true} if {@link WebSocketScenario#sendBinaryFromClient(byte[])} works
     */
    protected boolean supportsClientBinaryFrames() {
        return false;
    }

    /** The live connection a test drives, from the client's side. */
    public interface WebSocketScenario extends AutoCloseable {

        /** @return the status the client saw, empty when the handshake succeeded */
        Optional<HttpStatus> handshakeStatus();

        /** @return the subprotocol the server selected, empty when none was negotiated */
        Optional<String> negotiatedSubprotocol();

        /** Sends one text message from the client. */
        void sendFromClient(String message);

        /** Sends one logical message split across {@code fragments} continuation frames. */
        void sendFragmentedFromClient(String message, int fragments);

        /** Sends a binary frame, which the contract refuses. */
        void sendBinaryFromClient(byte[] payload);

        /** @return the next message the client received, or {@code null} on timeout/close */
        String receiveOnClient(long timeout, TimeUnit unit);

        /** Closes from the client with the given code. */
        void closeClient(WebSocketCloseCode code);

        /** @return the close code the client observed, or empty while the connection is open */
        Optional<Integer> observedCloseCode(long timeout, TimeUnit unit);

        @Override
        void close();
    }

    // -------------------------------------------------------------------------

    private WebSocketConfig config() {
        return WebSocketConfig.defaultServer("127.0.0.1", 0, List.of(ALLOWED_ORIGIN));
    }

    private static final class Captured {
        final BlockingQueue<String> received = new ArrayBlockingQueue<>(64);
        final AtomicReference<WebSocketSession> session = new AtomicReference<>();
        final AtomicReference<RuntimeException> sendFailure = new AtomicReference<>();
    }

    /** A handler that records what it saw and echoes every message back. */
    private static WebSocketHandler echo(Captured captured) {
        return exchange -> {
            captured.session.set(exchange.session());
            String message;
            while ((message = exchange.receive()) != null) {
                captured.received.offer(message);
                try {
                    exchange.send(message);
                } catch (WebSocketClosedException closed) {
                    captured.sendFailure.set(closed);
                    return;
                }
            }
        };
    }

    @Nested
    @DisplayName("messages")
    class Messages {

        @Test
        @DisplayName("a text message round-trips")
        void textRoundTrip() {
            Captured captured = new Captured();
            try (WebSocketScenario scenario = connect(config(), echo(captured), null, ALLOWED_ORIGIN)) {
                scenario.sendFromClient("hello");
                assertThat(scenario.receiveOnClient(WIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        .isEqualTo("hello");
            }
        }

        @Test
        @DisplayName("a fragmented message is reassembled before the handler sees it")
        void fragmentedMessageIsReassembled() {
            // A peer that splits a large message across continuation frames is speaking the protocol
            // correctly (ADR-084 §3). The handler must see one message, not three.
            Assumptions.assumeTrue(supportsClientFragmentation(),
                    "binding's client cannot fragment; reassembly not exercised");
            Captured captured = new Captured();
            String payload = "abcdefghij".repeat(30);
            try (WebSocketScenario scenario = connect(config(), echo(captured), null, ALLOWED_ORIGIN)) {
                scenario.sendFragmentedFromClient(payload, 3);
                assertThat(scenario.receiveOnClient(WIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        .as("the handler must be handed one message, not one per frame")
                        .isEqualTo(payload);
            }
        }

        @Test
        @DisplayName("a message over the configured limit closes with MESSAGE_TOO_BIG, never truncated")
        void oversizeMessageIsRefused() {
            // Truncating would hand the handler something the peer never sent, which is worse than
            // refusing: the handler cannot tell a short message from a clipped one.
            WebSocketConfig small = new WebSocketConfig("127.0.0.1", 0,
                    WebSocketConfig.DEFAULT_MAX_CONNECTIONS,
                    WebSocketConfig.DEFAULT_IDLE_TIMEOUT_MILLIS,
                    WebSocketConfig.DEFAULT_KEEP_ALIVE_INTERVAL_MILLIS,
                    64L, java.util.Set.of(ALLOWED_ORIGIN));
            Captured captured = new Captured();
            try (WebSocketScenario scenario = connect(small, echo(captured), null, ALLOWED_ORIGIN)) {
                scenario.sendFromClient("x".repeat(200));
                assertThat(scenario.observedCloseCode(WIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        .contains(WebSocketCloseCode.MESSAGE_TOO_BIG.code());
                assertThat(captured.received)
                        .as("an oversize message must not reach the handler at all")
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("a binary frame is refused — the SPI is text-only")
        void binaryFrameIsRefused() {
            Assumptions.assumeTrue(supportsClientBinaryFrames(),
                    "binding's client cannot send binary frames; refusal not exercised");
            Captured captured = new Captured();
            try (WebSocketScenario scenario = connect(config(), echo(captured), null, ALLOWED_ORIGIN)) {
                scenario.sendBinaryFromClient(new byte[]{1, 2, 3});
                assertThat(scenario.observedCloseCode(WIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        .isPresent();
                assertThat(captured.received).isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("close")
    class Close {

        @Test
        @DisplayName("receive() returns null once the peer closes, so the handler falls out of its loop")
        void receiveEndsOnClose() {
            Captured captured = new Captured();
            try (WebSocketScenario scenario = connect(config(), echo(captured), null, ALLOWED_ORIGIN)) {
                scenario.sendFromClient("one");
                assertThat(scenario.receiveOnClient(WIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        .isEqualTo("one");
                scenario.closeClient(WebSocketCloseCode.NORMAL_CLOSURE);
                // The handler returning is what proves receive() ended rather than blocking forever.
                assertThat(captured.sendFailure.get())
                        .as("a normal close must not surface as a send failure")
                        .isNull();
            }
        }

        @Test
        @DisplayName("a close code sent by the client is the one the server observes")
        void closeCodeRoundTrips() {
            // ADR-084 §8: a transport that can only say "closed" cannot tell a peer that went away
            // from one that broke the protocol.
            Captured captured = new Captured();
            try (WebSocketScenario scenario = connect(config(), echo(captured), null, ALLOWED_ORIGIN)) {
                scenario.closeClient(WebSocketCloseCode.GOING_AWAY);
                assertThat(scenario.observedCloseCode(WIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        .contains(WebSocketCloseCode.GOING_AWAY.code());
            }
        }

        @Test
        @DisplayName("send after close raises WebSocketClosedException, carrying no message content")
        void sendAfterCloseThrows() {
            AtomicReference<WebSocketExchange> held = new AtomicReference<>();
            WebSocketHandler capture = exchange -> {
                held.set(exchange);
                while (exchange.receive() != null) {  // NOPMD EmptyControlStatement - the condition does the draining
                    // drain until the peer goes
                }
            };
            try (WebSocketScenario scenario = connect(config(), capture, null, ALLOWED_ORIGIN)) {
                scenario.sendFromClient("warm-up");
                scenario.closeClient(WebSocketCloseCode.NORMAL_CLOSURE);
                scenario.observedCloseCode(WIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                WebSocketExchange exchange = held.get();
                assertThat(exchange).as("the handler must have been invoked").isNotNull();
                String secret = "authorization: Bearer s3cr3t";
                assertThatThrownBy(() -> exchange.send(secret))
                        .isInstanceOf(WebSocketClosedException.class)
                        .satisfies(thrown -> {
                            WebSocketClosedException closed = (WebSocketClosedException) thrown;
                            assertThat(closed.getMessage())
                                    .as("the failed message is the payload most likely to be sensitive")
                                    .doesNotContain(secret);
                            assertThat(closed.rawArgs())
                                    .as("rawArgs carry counters and a close code, never content")
                                    .noneMatch(arg -> String.valueOf(arg).contains(secret));
                        });
            }
        }
    }

    @Nested
    @DisplayName("handshake")
    class Handshake {

        @Test
        @DisplayName("an unlisted origin is refused with NO handshake callback written")
        void unlistedOriginRefusedByDefault() {
            // The half of ADR-084 §6 that matters: forgetting to write a callback must produce a
            // refusal, not a hole. A test asserting only acceptance passes against an engine that
            // accepts everything.
            Captured captured = new Captured();
            try (WebSocketScenario scenario =
                         connect(config(), echo(captured), null, UNLISTED_ORIGIN)) {
                assertThat(scenario.handshakeStatus())
                        .as("no callback was set; the allowlist must refuse on its own")
                        .isPresent();
            }
        }

        @Test
        @DisplayName("an unlisted origin is refused even when a callback would accept it")
        void allowlistIsAPreFilterTheCallbackCannotWiden() {
            // The other half of ADR-084 §6, and the half a binding gets wrong silently. The rule is
            // not "the allowlist refuses when nobody wrote a callback" -- it is that the allowlist
            // is a HARD pre-filter and a callback can only narrow it. An engine that consults the
            // callback first, or that treats a callback's presence as permission to skip the
            // allowlist, passes every other test in this class: the refusal test writes no callback,
            // and every test that writes one uses an allowed origin.
            //
            // A WebSocket handshake is not subject to CORS, so the failure this catches is a server
            // any page the victim has visited can open, carrying their cookies.
            Captured captured = new Captured();
            AtomicReference<Boolean> callbackRan = new AtomicReference<>(false);
            WebSocketHandshakeHandler acceptAnything = request -> {
                callbackRan.set(true);
                return WebSocketHandshake.accept();
            };
            try (WebSocketScenario scenario =
                         connect(config(), echo(captured), acceptAnything, UNLISTED_ORIGIN)) {
                assertThat(scenario.handshakeStatus())
                        .as("the allowlist is a pre-filter; a callback must not be able to widen it")
                        .isPresent();
                assertThat(callbackRan.get())
                        .as("the callback must not even run for an origin the allowlist rejected")
                        .isFalse();
            }
        }

        @Test
        @DisplayName("a callback may refuse, and the client receives its status")
        void callbackRefusalReachesTheClient() {
            Captured captured = new Captured();
            WebSocketHandshakeHandler refuse =
                    request -> WebSocketHandshake.refuse(HttpStatus.UNAUTHORIZED);
            try (WebSocketScenario scenario =
                         connect(config(), echo(captured), refuse, ALLOWED_ORIGIN)) {
                assertThat(scenario.handshakeStatus()).contains(HttpStatus.UNAUTHORIZED);
            }
        }

        @Test
        @DisplayName("a callback sees the request, and may accept naming a subprotocol")
        void callbackAcceptsAndNegotiates() {
            Captured captured = new Captured();
            AtomicReference<String> seenPath = new AtomicReference<>();
            WebSocketHandshakeHandler accept = request -> {
                seenPath.set(request.path());
                return WebSocketHandshake.accept("exeris.lsp.v1");
            };
            try (WebSocketScenario scenario =
                         connect(config(), echo(captured), accept, ALLOWED_ORIGIN)) {
                assertThat(scenario.handshakeStatus()).isEmpty();
                assertThat(seenPath.get())
                        .as("the callback must see the request, which is how a consumer authenticates")
                        .isNotNull();
                assertThat(scenario.negotiatedSubprotocol()).contains("exeris.lsp.v1");
                scenario.sendFromClient("ping");
                assertThat(scenario.receiveOnClient(WIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        .isEqualTo("ping");
                assertThat(captured.session.get().subprotocol())
                        .as("the negotiated subprotocol must reach the session the handler holds")
                        .contains("exeris.lsp.v1");
            }
        }
    }

    @Nested
    @DisplayName("session")
    class Session {

        @Test
        @DisplayName("identity is stable within a connection and distinct across connections")
        void identityIsPerConnection() {
            // ADR-084 §4: a reconnect is a new session by design, so the consumer that wants
            // continuity builds it on the handshake rather than expecting the id to persist.
            Captured first = new Captured();
            UUID firstId;
            try (WebSocketScenario scenario = connect(config(), echo(first), null, ALLOWED_ORIGIN)) {
                scenario.sendFromClient("a");
                scenario.receiveOnClient(WIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                firstId = first.session.get().id();
                assertThat(firstId).isNotNull();
                scenario.sendFromClient("b");
                scenario.receiveOnClient(WIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertThat(first.session.get().id())
                        .as("identity must not change under the handler mid-connection")
                        .isEqualTo(firstId);
            }

            Captured second = new Captured();
            try (WebSocketScenario scenario = connect(config(), echo(second), null, ALLOWED_ORIGIN)) {
                scenario.sendFromClient("a");
                scenario.receiveOnClient(WIRE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertThat(second.session.get().id())
                        .as("a second connection is a second session, never a resumption")
                        .isNotEqualTo(firstId);
            }
        }
    }
}
