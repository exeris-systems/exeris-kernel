/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.community.transport.MapConfigProvider;
import eu.exeris.kernel.spi.http.HttpConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two body limits resolve independently (ADR-071 amendment).
 *
 * <p>{@code http.maxRequestBodyBytes} bounds what this server accepts; {@code
 * http.maxResponseBodyBytes} bounds what this client reads back from someone else's. Until 0.12 the
 * client borrowed the request key, so tightening ingress shrank what the outbound client could read
 * and loosening it grew every response allocation — with neither name saying so.
 *
 * <p>The load-bearing cases are the two negatives: setting one key must leave the other at its
 * default. A test that only asserted each key is honoured would pass just as well against the
 * shared-knob version this replaces.
 */
@DisplayName("Community HTTP config: the request and response body limits are separate keys")
class CommunityHttpBodyLimitKeysTest {

    private static final long CONFIGURED = 512L * 1024;

    private static HttpConfig resolve(Map<String, Long> longs) {
        return CommunityHttpConfigResolver.buildHttpConfig(
                new MapConfigProvider(Map.of("http.mode", "SERVER"), Map.of(), longs));
    }

    @Nested
    @DisplayName("Each key is honoured")
    class Honoured {

        @Test
        @DisplayName("http.maxResponseBodyBytes reaches the client ceiling")
        void responseKeyIsRead() {
            assertThat(resolve(Map.of("http.maxResponseBodyBytes", CONFIGURED)).maxResponseBodyBytes())
                    .isEqualTo(CONFIGURED);
        }

        @Test
        @DisplayName("http.maxRequestBodyBytes reaches the server ceiling")
        void requestKeyIsRead() {
            assertThat(resolve(Map.of("http.maxRequestBodyBytes", CONFIGURED)).maxRequestBodyBytes())
                    .isEqualTo(CONFIGURED);
        }
    }

    @Nested
    @DisplayName("Neither key moves the other")
    class Independent {

        @Test
        @DisplayName("tuning ingress leaves the client ceiling at its default")
        void requestKeyDoesNotMoveTheResponseCeiling() {
            assertThat(resolve(Map.of("http.maxRequestBodyBytes", CONFIGURED)).maxResponseBodyBytes())
                    .as("this is the defect the split exists to remove — a deployment bounding what "
                            + "it accepts did not ask to bound what it can read back")
                    .isEqualTo(HttpConfig.DEFAULT_MAX_RESPONSE_BODY_BYTES);
        }

        @Test
        @DisplayName("tuning the client leaves the ingress ceiling at its default")
        void responseKeyDoesNotMoveTheRequestCeiling() {
            assertThat(resolve(Map.of("http.maxResponseBodyBytes", CONFIGURED)).maxRequestBodyBytes())
                    .as("and the same in the other direction, or the split is only half made")
                    .isEqualTo(HttpConfig.DEFAULT_MAX_REQUEST_BODY_BYTES);
        }

        @Test
        @DisplayName("with neither set, both carry their own default")
        void bothDefault() {
            HttpConfig config = resolve(Map.of());
            assertThat(config.maxRequestBodyBytes()).isEqualTo(HttpConfig.DEFAULT_MAX_REQUEST_BODY_BYTES);
            assertThat(config.maxResponseBodyBytes()).isEqualTo(HttpConfig.DEFAULT_MAX_RESPONSE_BODY_BYTES);
        }
    }
}
