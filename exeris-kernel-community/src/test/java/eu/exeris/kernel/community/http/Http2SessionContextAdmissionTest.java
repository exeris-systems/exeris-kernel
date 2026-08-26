/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests {@link Http2SessionContext#admitClientStreamId(int)} — HTTP-112 (v0.8 Sprint 5).
 *
 * <p>Covers RFC 7540 §5.1.1 (peer-initiated stream IDs MUST be odd and strictly
 * increasing) and §5.1.2 ({@code SETTINGS_MAX_CONCURRENT_STREAMS} cap, currently
 * 100 by default in {@link Http2SessionContext}).
 */
@DisplayName("Http2SessionContext — admission decision (HTTP-112)")
final class Http2SessionContextAdmissionTest {

    private MemoryAllocator allocator;
    private Http2SessionContext session;

    @BeforeEach
    void setUp() {
        allocator = new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
        session = Http2SessionContext.create(allocator);
    }

    @AfterEach
    void tearDown() {
        session.close();
        allocator.close();
    }

    @Nested
    @DisplayName("RFC 7540 §5.1.1 stream-id monotonicity")
    class Monotonicity {

        @Test
        @DisplayName("first odd id (1) is accepted")
        void firstOddIdAccepted() {
            assertThat(session.admitClientStreamId(1))
                    .isEqualTo(Http2SessionContext.StreamAdmission.ACCEPT);
            assertThat(session.lastClientStreamId()).isEqualTo(1);
        }

        @Test
        @DisplayName("strictly increasing odd ids are accepted in sequence")
        void strictlyIncreasingOddIdsAccepted() {
            assertThat(session.admitClientStreamId(1))
                    .isEqualTo(Http2SessionContext.StreamAdmission.ACCEPT);
            assertThat(session.admitClientStreamId(3))
                    .isEqualTo(Http2SessionContext.StreamAdmission.ACCEPT);
            assertThat(session.admitClientStreamId(5))
                    .isEqualTo(Http2SessionContext.StreamAdmission.ACCEPT);
            assertThat(session.lastClientStreamId()).isEqualTo(5);
        }

        @Test
        @DisplayName("even id is rejected as invalid (server-reserved)")
        void evenIdRejected() {
            assertThat(session.admitClientStreamId(2))
                    .isEqualTo(Http2SessionContext.StreamAdmission.REJECT_INVALID_ID);
            // High watermark must NOT advance on a rejection
            assertThat(session.lastClientStreamId()).isZero();
        }

        @Test
        @DisplayName("id equal to high-water is rejected as invalid (strict increase)")
        void idEqualToHighWaterRejected() {
            session.admitClientStreamId(5);
            assertThat(session.admitClientStreamId(5))
                    .isEqualTo(Http2SessionContext.StreamAdmission.REJECT_INVALID_ID);
            assertThat(session.lastClientStreamId()).isEqualTo(5);
        }

        @Test
        @DisplayName("id below high-water is rejected as invalid (no reuse)")
        void idBelowHighWaterRejected() {
            session.admitClientStreamId(7);
            assertThat(session.admitClientStreamId(3))
                    .isEqualTo(Http2SessionContext.StreamAdmission.REJECT_INVALID_ID);
            assertThat(session.lastClientStreamId()).isEqualTo(7);
        }
    }

    @Nested
    @DisplayName("RFC 7540 §5.1.2 max-concurrent cap")
    class MaxConcurrentCap {

        @Test
        @DisplayName("admission past the cap returns REJECT_OVER_CAP")
        void admissionPastCapRejected() {
            // Open and KEEP open `maxConcurrentStreams` streams. Use openRequestStream to
            // populate the requestStreams map (the cap reads requestStreams.size()).
            int cap = session.maxConcurrentStreams();
            for (int idx = 0; idx < cap; idx++) {
                int streamId = idx * 2 + 1; // 1, 3, 5, ...
                assertThat(session.admitClientStreamId(streamId))
                        .as("stream %d below cap should ACCEPT", streamId)
                        .isEqualTo(Http2SessionContext.StreamAdmission.ACCEPT);
                session.openRequestStream(
                        new Http2DecodedRequest(streamId, null, "/", java.util.List.of(), true));
            }
            int overCapStreamId = cap * 2 + 1;
            assertThat(session.admitClientStreamId(overCapStreamId))
                    .isEqualTo(Http2SessionContext.StreamAdmission.REJECT_OVER_CAP);
            // OVER_CAP must NOT advance high-water; another stream with the same id
            // could legally be re-admitted later if the table drains.
            assertThat(session.lastClientStreamId()).isEqualTo((cap - 1) * 2 + 1);
        }

        @Test
        @DisplayName("INVALID_ID check precedes OVER_CAP check")
        void invalidIdTakesPrecedenceOverOverCap() {
            // Fill the table to the cap.
            int cap = session.maxConcurrentStreams();
            for (int idx = 0; idx < cap; idx++) {
                int streamId = idx * 2 + 1;
                session.admitClientStreamId(streamId);
                session.openRequestStream(
                        new Http2DecodedRequest(streamId, null, "/", java.util.List.of(), true));
            }
            // Even id at the cap → still rejected as INVALID_ID first (protocol error wins
            // over cap error so the peer sees the right diagnostic).
            assertThat(session.admitClientStreamId(2))
                    .isEqualTo(Http2SessionContext.StreamAdmission.REJECT_INVALID_ID);
        }
    }
}
