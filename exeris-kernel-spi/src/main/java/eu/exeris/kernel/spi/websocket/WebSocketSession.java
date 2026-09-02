/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.websocket;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * SPI: the identity of one accepted connection.
 *
 * <p>Stable for the connection's lifetime and no longer (ADR-084 §4). A consumer whose model is one
 * server instance per session keys that instance on {@link #id()}; without it the model would have
 * to reconstruct session affinity from a socket this SPI does not expose.
 *
 * <h2>It does not survive a reconnect, and that is the decision</h2>
 * <p>A sleeping browser tab or a flapping network produces a new connection and a new id. A consumer
 * that wants continuity across that builds it on the handshake — a returning client presents its own
 * token, which the consumer maps back to its own session object.
 *
 * <p>The kernel does not own resumption because the cost concentrates in buffering the disconnect
 * window, which is the on-heap queue ADR-043 obligation 4 forbids; and without that buffer,
 * resumption restores <em>identity, not the stream</em>, so the consumer reconciles state anyway. It
 * is the same division ADR-013 draws for saga state: the kernel supplies a seam, the application
 * holds what must outlive a process.
 *
 * @param id          identity for this connection's lifetime; never {@code null}
 * @param subprotocol the negotiated {@code Sec-WebSocket-Protocol}, empty when none was agreed
 * @param isolationKey the tenant scope captured from the ambient {@code StorageContext} at
 *                     handshake, empty for an unscoped connection. Captured rather than owned here:
 *                     it stops being ambient and becomes the connection's, but its source is the
 *                     established context
 * @since 0.12.0
 */
// `id` follows StreamEvent, which carries the same suppression for the same reason: it is the
// name the field has everywhere it is read, and lengthening it to satisfy a rule would make the
// carrier read worse than the code around it.
@SuppressWarnings("PMD.ShortVariable")
public record WebSocketSession(
        UUID id,
        Optional<String> subprotocol,
        Optional<String> isolationKey
) {

    public WebSocketSession {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(subprotocol, "subprotocol must not be null");
        Objects.requireNonNull(isolationKey, "isolationKey must not be null");
    }
}
