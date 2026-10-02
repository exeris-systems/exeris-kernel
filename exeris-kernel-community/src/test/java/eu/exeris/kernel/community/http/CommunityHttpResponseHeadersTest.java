/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.spi.http.HttpHeader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CommunityHttpResponseHeaders#merge} is the single copy of the concatenation rule. There were
 * two — the HTTP/2 exchange carried a byte-identical private one — and nothing covered either.
 */
@DisplayName("CommunityHttpResponseHeaders")
class CommunityHttpResponseHeadersTest {

    private static final HttpHeader TYPE = new HttpHeader("Content-Type", "application/json");
    private static final HttpHeader ENCODING = new HttpHeader("Content-Encoding", "gzip");
    private static final HttpHeader ETAG = new HttpHeader("ETag", "\"a3f1\"");

    @Nested
    @DisplayName("merge")
    class Merge {

        @Test
        @DisplayName("returns the encoder's list unchanged when the application supplied none")
        void typedEmptyReturnsEncoded() {
            List<HttpHeader> encoded = List.of(TYPE, ENCODING);
            assertThat(CommunityHttpResponseHeaders.merge(List.of(), encoded)).isSameAs(encoded);
        }

        @Test
        @DisplayName("returns the application's list unchanged when the encoder supplied none")
        void encodedEmptyReturnsTyped() {
            List<HttpHeader> typed = List.of(ETAG);
            assertThat(CommunityHttpResponseHeaders.merge(typed, List.of())).isSameAs(typed);
        }

        @Test
        @DisplayName("concatenates application headers before encoder headers")
        void concatenatesInOrder() {
            assertThat(CommunityHttpResponseHeaders.merge(List.of(ETAG), List.of(TYPE, ENCODING)))
                    .containsExactly(ETAG, TYPE, ENCODING);
        }

        @Test
        @DisplayName("leaves both inputs untouched")
        void doesNotMutateInputs() {
            List<HttpHeader> typed = new ArrayList<>(List.of(ETAG));
            List<HttpHeader> encoded = new ArrayList<>(List.of(TYPE));
            CommunityHttpResponseHeaders.merge(typed, encoded);
            assertThat(typed).containsExactly(ETAG);
            assertThat(encoded).containsExactly(TYPE);
        }

        @Test
        @DisplayName("returns an empty list when neither side supplied a header")
        void bothEmpty() {
            assertThat(CommunityHttpResponseHeaders.merge(List.of(), List.of())).isEmpty();
        }
    }
}
