/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.tck.contract.transport;

import eu.exeris.kernel.spi.transport.TransportConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
 *   <li>{@code remotePort()} is in valid range</li>
 *   <li>{@code openStream()} returns a valid stream on an open connection</li>
 *   <li>Attachment pattern: set/get works correctly</li>
 * </ul>
 *
 * @since 0.5.0
 */
public abstract class AbstractTransportConnectionTck {

    /**
     * Creates a pair of connected {@link TransportConnection} instances (e.g., via loopback).
     * Returns both as a record — the test verifies the server-side connection.
     */
    protected abstract ConnectionPair createConnectionPair();

    /**
     * Connection pair for testing — server side is the SUT.
     */
    protected record ConnectionPair(TransportConnection server, TransportConnection client) {
    }

    private ConnectionPair pair;

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

        @Test
        @DisplayName("remoteAddress() is non-null and non-blank")
        void remoteAddressNonNull() {
            assertThat(pair.server().remoteAddress()).isNotNull().isNotBlank();
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
