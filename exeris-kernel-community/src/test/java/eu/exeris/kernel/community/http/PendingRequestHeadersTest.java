/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Adversarial unit coverage for {@link PendingRequestHeaders} HPACK-decode validation
 * (v0.9 Sprint 4c Phase 2). Exercises RFC 7540 §8.1.2 request-pseudo-header rules as a
 * fail-closed contract. These live as Community unit tests (not a portable TCK) because
 * HTTP/2 frame decode has no SPI seam — the validator is internal Community runtime.
 */
@DisplayName("HTTP/2: PendingRequestHeaders pseudo-header validation")
class PendingRequestHeadersTest {

    private static final int STREAM_ID = 1;

    @Nested
    @DisplayName("well-formed requests")
    class WellFormed {

        @Test
        @DisplayName("minimal :method + :path request is valid")
        void minimalRequestIsValid() {
            PendingRequestHeaders headers = new PendingRequestHeaders();
            headers.accept(":method", "GET");
            headers.accept(":path", "/");

            Http2DecodedRequest request = headers.toDecodedRequest(STREAM_ID);

            assertThat(request.valid()).isTrue();
            assertThat(request.path()).isEqualTo("/");
        }

        @Test
        @DisplayName("all four recognised pseudo-headers are accepted once each")
        void allPseudoHeadersOnceIsValid() {
            PendingRequestHeaders headers = new PendingRequestHeaders();
            headers.accept(":method", "GET");
            headers.accept(":scheme", "https");
            headers.accept(":authority", "example.com");
            headers.accept(":path", "/resource");
            headers.accept("x-custom", "value");

            assertThat(headers.toDecodedRequest(STREAM_ID).valid()).isTrue();
        }
    }

    @Nested
    @DisplayName("duplicate pseudo-headers (RFC 7540 §8.1.2.3) — smuggling vector")
    class DuplicatePseudoHeaders {

        @Test
        @DisplayName("duplicate :path is rejected (no last-wins overwrite)")
        void duplicatePathIsRejected() {
            PendingRequestHeaders headers = new PendingRequestHeaders();
            headers.accept(":method", "GET");
            headers.accept(":path", "/safe");
            headers.accept(":path", "/admin");

            Http2DecodedRequest request = headers.toDecodedRequest(STREAM_ID);

            assertThat(request.valid()).isFalse();
            // The first value must NOT be silently overwritten by the smuggled second value.
            assertThat(request.path()).isEqualTo("/safe");
        }

        @Test
        @DisplayName("duplicate :method is rejected")
        void duplicateMethodIsRejected() {
            PendingRequestHeaders headers = new PendingRequestHeaders();
            headers.accept(":method", "GET");
            headers.accept(":method", "POST");
            headers.accept(":path", "/");

            assertThat(headers.toDecodedRequest(STREAM_ID).valid()).isFalse();
        }

        @Test
        @DisplayName("duplicate :authority is rejected")
        void duplicateAuthorityIsRejected() {
            PendingRequestHeaders headers = new PendingRequestHeaders();
            headers.accept(":method", "GET");
            headers.accept(":authority", "example.com");
            headers.accept(":authority", "evil.example.net");
            headers.accept(":path", "/");

            assertThat(headers.toDecodedRequest(STREAM_ID).valid()).isFalse();
        }
    }

    @Nested
    @DisplayName("malformed pseudo-header usage")
    class Malformed {

        @Test
        @DisplayName("pseudo-header after a regular header is rejected (§8.1.2.1 ordering)")
        void pseudoAfterRegularIsRejected() {
            PendingRequestHeaders headers = new PendingRequestHeaders();
            headers.accept(":method", "GET");
            headers.accept("x-custom", "value");
            headers.accept(":path", "/");

            assertThat(headers.toDecodedRequest(STREAM_ID).valid()).isFalse();
        }

        @Test
        @DisplayName("unknown pseudo-header is rejected")
        void unknownPseudoHeaderIsRejected() {
            PendingRequestHeaders headers = new PendingRequestHeaders();
            headers.accept(":method", "GET");
            headers.accept(":path", "/");
            headers.accept(":evil", "1");

            assertThat(headers.toDecodedRequest(STREAM_ID).valid()).isFalse();
        }
    }
}
