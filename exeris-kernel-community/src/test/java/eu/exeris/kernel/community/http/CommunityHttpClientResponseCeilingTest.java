/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpMode;
import eu.exeris.kernel.spi.http.HttpVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The client sizes a response read from the <em>response</em> ceiling.
 *
 * <p>Until 0.12 it read {@code maxRequestBodyBytes} — the limit that bounds what this <em>server</em>
 * accepts — so a deployment tightening its ingress also shrank what its outbound client could read
 * back, and loosening it grew every response allocation. Two directions, two sockets, one knob.
 *
 * <p>Asserted on the derived capacity rather than through a live peer: the number is what the
 * allocation uses, and a test that has to stand up a server to reach it would be measuring the
 * server too.
 */
@DisplayName("Community HTTP client: the response ceiling is the response ceiling")
class CommunityHttpClientResponseCeilingTest {

    private static final long REQUEST_CEILING = 2L * 1024 * 1024;
    private static final long RESPONSE_CEILING = 32L * 1024;
    private static final long HEADER_ALLOWANCE = 8L * 1024;

    @Test
    @DisplayName("the aggregate follows maxResponseBodyBytes, and moving the request limit does not move it")
    void aggregateFollowsTheResponseCeiling() {
        try (CommunityHttpClientEngine engine = new CommunityHttpClientEngine(
                clientConfig(REQUEST_CEILING, RESPONSE_CEILING))) {

            assertThat(engine.resolveAggregateCapacity())
                    .as("sized from the response ceiling plus the header allowance")
                    .isEqualTo((int) (RESPONSE_CEILING + HEADER_ALLOWANCE));
        }

        // The discriminating half: the request ceiling moves by two orders of magnitude and the
        // response allocation must not notice. Before the split this assertion could not hold.
        try (CommunityHttpClientEngine engine = new CommunityHttpClientEngine(
                clientConfig(REQUEST_CEILING * 100, RESPONSE_CEILING))) {

            assertThat(engine.resolveAggregateCapacity())
                    .as("an ingress limit is not an egress limit")
                    .isEqualTo((int) (RESPONSE_CEILING + HEADER_ALLOWANCE));
        }
    }

    @Test
    @DisplayName("a ceiling near the int range still yields a usable capacity, not a negative one")
    void largeCeilingsAreClampedNotWrapped() {
        // 2 GiB is a legal value for the key. The header allowance pushes it past Integer.MAX_VALUE,
        // and a bare cast lands on a negative number the allocator refuses — so a deployment that
        // set a large limit failed on its first response, on the limit itself.
        try (CommunityHttpClientEngine engine = new CommunityHttpClientEngine(
                clientConfig(REQUEST_CEILING, 2L * 1024 * 1024 * 1024))) {

            assertThat(engine.resolveAggregateCapacity())
                    .as("clamped to the largest allocatable capacity")
                    .isEqualTo(Integer.MAX_VALUE);
        }
    }

    @Test
    @DisplayName("the pre-0.12 constructor shape keeps its single ceiling, so an old caller is unchanged")
    void bridgeKeepsTheOldCoupling() {
        HttpConfig bridged = new HttpConfig(
                HttpMode.CLIENT, "127.0.0.1", -1, 8, 30_000L, 100, 8_192,
                REQUEST_CEILING, false, HttpVersion.HTTP_1_1, "127.0.0.1:9", 65_536, 65_536, 65_536);

        assertThat(bridged.maxResponseBodyBytes())
                .as("a caller who passed one number for an engine they built themselves keeps one "
                        + "number — silently lowering them to a default they never named would turn "
                        + "an upgrade into a run-time truncation")
                .isEqualTo(REQUEST_CEILING);
    }

    private static HttpConfig clientConfig(long requestCeiling, long responseCeiling) {
        return new HttpConfig(
                HttpMode.CLIENT, "127.0.0.1", -1, 8, 30_000L, 100, 8_192,
                requestCeiling, false, HttpVersion.HTTP_1_1, "127.0.0.1:9",
                65_536, 65_536, 65_536, responseCeiling);
    }
}
