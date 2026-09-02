/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.websocket;

import eu.exeris.kernel.spi.http.HttpMethod;
import eu.exeris.kernel.spi.http.HttpRequest;
import eu.exeris.kernel.spi.http.HttpStatus;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.transport.TransportStream;
import eu.exeris.kernel.spi.websocket.WebSocketConfig;
import eu.exeris.kernel.spi.websocket.WebSocketHandshake;
import eu.exeris.kernel.spi.websocket.WebSocketHandshakeHandler;

import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Optional;

/**
 * The opening HTTP request, and the decision to speak WebSocket over the same connection.
 *
 * <p><b>The origin allowlist is a hard pre-filter and the callback can only narrow it</b> (ADR-084
 * §6). A WebSocket handshake is not subject to CORS: a server that ignores {@code Origin} can be
 * opened by any page the victim has visited, carrying their cookies, and a browser cannot set
 * request headers to say otherwise. So an unlisted origin is refused before a callback runs at all —
 * a consumer that needs a wider set widens the allowlist, which is visible in configuration, rather
 * than re-opening it inside a callback nobody re-reads.
 */
final class CommunityWebSocketUpgrade {

    /** RFC 6455 §4.2.2: the GUID the key is concatenated with before hashing. */
    private static final String ACCEPT_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private static final int SUPPORTED_VERSION = 13;

    private static final String CRLF = "\r\n";

    private static final String UPGRADE_TOKEN = "Upgrade";

    private CommunityWebSocketUpgrade() {
    }

    /** The outcome, so the caller can tell "speak WebSocket" from "the connection is finished". */
    /* default */ record Outcome(boolean accepted, Optional<String> subprotocol) {
        /* default */ static Outcome refused() {
            return new Outcome(false, Optional.empty());
        }
    }

    /* default */ static Outcome negotiate(TransportStream stream, WebSocketConfig config,
                                           WebSocketHandshakeHandler handshakeHandler,
                                           MemoryAllocator allocator) {
        CommunityWebSocketUpgradeRequest.Parsed parsed =
                CommunityWebSocketUpgradeRequest.read(stream, allocator);
        if (parsed.request() == null) {
            // Nothing usable arrived. A request that never completed within the cap gets NO status:
            // a peer that did not finish a request line is not owed a response, and answering an
            // unparsed stream is how a scanner gets a reply worth measuring. A request that WAS
            // complete but is not a GET is well-formed and gets its 400.
            if (parsed.malformed()) {
                respondRefusal(stream, HttpStatus.BAD_REQUEST, allocator);
            }
            return Outcome.refused();
        }
        HttpRequest request = parsed.request();
        String key = request.firstHeader("Sec-WebSocket-Key").orElse(null);
        if (!isUpgradeRequest(request) || key == null || key.isBlank()) {
            respondRefusal(stream, HttpStatus.BAD_REQUEST, allocator);
            return Outcome.refused();
        }

        // The pre-filter, before any callback runs at all.
        //
        // An EMPTY allowlist refuses every browser origin rather than admitting any -- the subsystem
        // contract says so, and it is the direction that fails closed: a config that forgot to list
        // its origins should stop working visibly, not open silently.
        //
        // A request carrying NO Origin is not a browser and the allowlist does not apply to it.
        // That is deliberate rather than an oversight: the attack this defends against is CSWSH,
        // where the victim's own browser supplies ambient cookies, and a client that can choose its
        // headers has no ambient credentials to be abused. Refusing header-less clients would break
        // every non-browser consumer -- the LSP over a plain socket among them -- while stopping an
        // attacker who need only omit one header.
        String origin = request.firstHeader("Origin").orElse(null);
        if (origin != null && !config.allowedOrigins().contains(origin)) {
            respondRefusal(stream, HttpStatus.FORBIDDEN, allocator);
            return Outcome.refused();
        }

        return applyCallback(stream, request, key, handshakeHandler, allocator);
    }

    private static Outcome applyCallback(TransportStream stream, HttpRequest request, String key,
                                         WebSocketHandshakeHandler handshakeHandler,
                                         MemoryAllocator allocator) {
        WebSocketHandshake decision = handshakeHandler == null
                ? WebSocketHandshake.accept()
                : handshakeHandler.decide(request);
        if (decision == null || !decision.accepted()) {
            // A callback returning null is treated as a refusal rather than an acceptance: the
            // fail-closed reading is the only safe one for a method whose whole job is admission.
            HttpStatus status = decision == null
                    ? HttpStatus.FORBIDDEN
                    : decision.refusalStatus().orElse(HttpStatus.FORBIDDEN);
            respondRefusal(stream, status, allocator);
            return Outcome.refused();
        }
        respondAccept(stream, key, decision.subprotocol().orElse(null), allocator);
        return new Outcome(true, decision.subprotocol());
    }

    private static boolean isUpgradeRequest(HttpRequest request) {
        // Both header values are token-based and case-insensitive per RFC 9110; Connection is a
        // comma-separated LIST, so a browser sending "keep-alive, Upgrade" must still match.
        boolean upgrade = request.firstHeader("Upgrade")
                .map(value -> "websocket".equalsIgnoreCase(value.trim()))
                .orElse(false);
        boolean connection = request.firstHeader("Connection")
                .map(value -> {
                    for (String token : value.split(",")) {
                        if (UPGRADE_TOKEN.equalsIgnoreCase(token.trim())) {
                            return true;
                        }
                    }
                    return false;
                })
                .orElse(false);
        boolean version = request.firstHeader("Sec-WebSocket-Version")
                .map(value -> {
                    try {
                        return Integer.parseInt(value.trim()) == SUPPORTED_VERSION;
                    } catch (NumberFormatException _) {
                        return false;
                    }
                })
                .orElse(false);
        return request.method() == HttpMethod.GET && upgrade && connection && version;
    }





    private static void respondAccept(TransportStream stream, String key, String subprotocol,
                                      MemoryAllocator allocator) {
        StringBuilder response = new StringBuilder(160)
                .append("HTTP/1.1 101 Switching Protocols" + CRLF
                        + "Upgrade: websocket" + CRLF
                        + "Connection: " + UPGRADE_TOKEN + CRLF
                        + "Sec-WebSocket-Accept: ")
                .append(acceptToken(key)).append(CRLF);
        if (subprotocol != null) {
            response.append("Sec-WebSocket-Protocol: ").append(subprotocol).append(CRLF);
        }
        response.append(CRLF);
        writeAscii(stream, response.toString(), allocator);
    }

    private static void respondRefusal(TransportStream stream, HttpStatus status,
                                       MemoryAllocator allocator) {
        // Connection: close, and no body. A refused upgrade has nothing to say that is not already
        // in the status, and a body on a connection the client expects to become a WebSocket would
        // be read as frames.
        writeAscii(stream, "HTTP/1.1 " + status.code() + " " + status.reasonPhrase() + CRLF
                + "Connection: close" + CRLF
                + "Content-Length: 0" + CRLF + CRLF, allocator);
    }

    private static String acceptToken(String key) {
        try {
            // SHA-1 here is RFC 6455 §4.2.2 and is not a security control: the token proves the
            // server understood the handshake, not that anyone is who they say. Substituting a
            // stronger digest would simply fail every conforming client.
            @SuppressWarnings("java:S4790") // see the paragraph above: RFC-mandated, not a security control
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hashed = digest.digest((key.trim() + ACCEPT_GUID)
                    .getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(hashed);
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-1 is required by RFC 6455 and is unavailable",
                    unavailable);
        }
    }

    // Off-heap for the reason recorded on CommunityWebSocketEgress: TransportStream.write documents
    // an off-heap source, and a heap segment is rejected by the POSIX send() downcall and swallowed
    // into the NIO fallback rather than failing. The handshake response is written once per
    // connection, so a scoped allocation is right here where the frame path reuses one buffer.
    private static void writeAscii(TransportStream stream, String text, MemoryAllocator allocator) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        try (LoanedBuffer outbound = allocator.allocateNetwork(bytes.length)) {
            MemorySegment.copy(MemorySegment.ofArray(bytes), 0, outbound.segment(), 0, bytes.length);
            outbound.setSize(bytes.length);
            stream.write(outbound.segment(), bytes.length);
        }
    }
}
