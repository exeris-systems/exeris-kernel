/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.transport;

import eu.exeris.kernel.spi.transport.TransportConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * TCK: Abstract base for {@link TransportConnection} contract verification.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@code isOpen()} returns {@code true} after creation</li>
 *   <li>{@code isOpen()} returns {@code false} after {@code close()}</li>
 *   <li>{@code close()} is idempotent</li>
 *   <li>{@code remoteAddress()} is non-null</li>
 *   <li>{@code remoteAddress()} is the peer's IP address in textual form on both ends: on the
 *       accepted end the address the peer connected from, on the dialled end the address the
 *       connection reached, never the host name it was dialled by</li>
 *   <li>{@code remotePort()} is in valid range</li>
 *   <li>{@code openStream()} returns a valid stream on an open connection</li>
 *   <li>Attachment pattern: set/get works correctly</li>
 * </ul>
 *
 * @since 0.5
 */
public abstract class AbstractTransportConnectionTck {

    /**
     * Creates a pair of connected {@link TransportConnection} instances (e.g., via loopback).
     *
     * @return a pair whose two ends are already connected to each other
     * @implSpec Both connections must be open and joined to each other on return; the suite
     *           performs no connection or handshake step of its own. Where the transport dials by
     *           name, open the client end by dialling a host name that resolves to the server
     *           rather than an address literal: the dialled-end address case can tell the address
     *           a connection reports from the name it was dialled by only when the two differ.
     */
    protected abstract ConnectionPair createConnectionPair();

    /**
     * Connection pair for testing — server side is the SUT.
     *
     * @param server the connection under test
     * @param client the connection's peer
     */
    public record ConnectionPair(TransportConnection server, TransportConnection client) {
    }

    private ConnectionPair pair;

    /**
     * Creates the contract; subclasses supply a connected pair via
     * {@link #createConnectionPair()}.
     *
     * <p>The {@code pair} field starts unset — {@link #setUpConnection()} populates it before
     * each test.
     */
    public AbstractTransportConnectionTck() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

    @BeforeEach
    final void setUpConnection() {
        pair = createConnectionPair();
    }

    @AfterEach
    final void tearDownConnection() {
        pair.server().close();
        pair.client().close();
    }

    // =========================================================================
    // Liveness
    // =========================================================================

    @Nested
    @DisplayName("Liveness contract")
    class Liveness {

        /**
         * Creates a fixture with no state of its own; JUnit constructs one instance per
         * {@code @Test} method below to exercise the liveness contract.
         */
        Liveness() {
            // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
            super();
        }

        @Test
        @DisplayName("isOpen() returns true after creation")
        void openAfterCreation() {
            assertThat(pair.server().isOpen()).isTrue();
        }

        @Test
        @DisplayName("isOpen() returns false after close()")
        void closedAfterClose() {
            pair.server().close();
            assertThat(pair.server().isOpen()).isFalse();
        }

        @Test
        @DisplayName("close() is idempotent")
        void closeIsIdempotent() {
            pair.server().close();
            assertThatCode(() -> pair.server().close()).doesNotThrowAnyException();
        }
    }

    // =========================================================================
    // Remote endpoint
    // =========================================================================

    @Nested
    @DisplayName("Remote endpoint contract")
    class RemoteEndpoint {

        /**
         * Creates a fixture with no state of its own; JUnit constructs one instance per
         * {@code @Test} method below to exercise the remote endpoint contract.
         */
        RemoteEndpoint() {
            // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
            super();
        }

        @Test
        @DisplayName("remoteAddress() is non-null and non-blank")
        void remoteAddressNonNull() {
            assertThat(pair.server().remoteAddress()).isNotNull().isNotBlank();
        }

        @Test
        @DisplayName("remoteAddress() of the accepted end is the peer's IP address")
        void acceptedEndReportsAnAddress() {
            String reported = pair.server().remoteAddress();
            assertThatCode(() -> InetAddress.ofLiteral(reported))
                    .as("the accepted end must report the address the peer connected from, got %s", reported)
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("remoteAddress() of the dialled end is the peer's IP address, not the name it was dialled by")
        void dialledEndReportsAnAddress() {
            // TransportConnection#remoteAddress is an address. A layer above the transport that
            // needs the name its caller wrote (an HTTP client writing Host, a TLS client sending
            // SNI) keeps that name itself; the connection reports where it actually connected.
            String reported = pair.client().remoteAddress();
            assertThatCode(() -> InetAddress.ofLiteral(reported))
                    .as("the dialled end must report the address it reached, not the host name it was dialled by, got %s",
                            reported)
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("remotePort() is in valid range (1–65535)")
        void remotePortValid() {
            assertThat(pair.server().remotePort())
                    .isGreaterThanOrEqualTo(1)
                    .isLessThanOrEqualTo(65_535);
        }
    }

    // =========================================================================
    // Stream creation
    // =========================================================================

    @Nested
    @DisplayName("Stream creation contract")
    class StreamCreation {

        /**
         * Creates a fixture with no state of its own; JUnit constructs one instance per
         * {@code @Test} method below to exercise the stream creation contract.
         */
        StreamCreation() {
            // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
            super();
        }

        @Test
        @DisplayName("openStream() returns a non-null stream on an open connection")
        void openStreamReturnsNonNull() {
            var stream = pair.server().openStream();
            assertThat(stream).isNotNull();
            stream.close();
        }
    }

    // =========================================================================
    // Attachment pattern
    // =========================================================================

    @Nested
    @DisplayName("Attachment pattern")
    class AttachmentPattern {

        /**
         * Creates a fixture with no state of its own; JUnit constructs one instance per
         * {@code @Test} method below to exercise the attachment pattern.
         */
        AttachmentPattern() {
            // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
            super();
        }

        @Test
        @DisplayName("attachment() returns null by default")
        void defaultAttachmentIsNull() {
            assertThat(pair.server().attachment()).isNull();
        }

        @Test
        @DisplayName("setAttachment() / attachment() round-trip")
        void attachmentRoundTrip() {
            Object marker = new Object();
            pair.server().setAttachment(marker);
            assertThat(pair.server().attachment()).isSameAs(marker);
        }

        @Test
        @DisplayName("setAttachment(null) clears the attachment")
        void clearAttachment() {
            pair.server().setAttachment("data");
            pair.server().setAttachment(null);
            assertThat(pair.server().attachment()).isNull();
        }
    }
}
