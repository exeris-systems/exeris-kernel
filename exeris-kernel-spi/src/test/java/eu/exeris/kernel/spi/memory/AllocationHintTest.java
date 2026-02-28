/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L0 Contract: {@link AllocationHint} enum — size correctness and basic enum invariants.
 *
 * <h2>Size Invariants</h2>
 * <p>These tests pin the {@code sizeBytes()} values (and, where applicable, the
 * number of {@link AllocationHint} constants). The Core engine relies on these
 * invariants when mapping allocation hints into pool-selection tables to uphold
 * the "No Waste Compute" mandate.
 *
 * @since 0.5.0
 */
@DisplayName("L0: AllocationHint — Size Contract + Ordinal Stability")
class AllocationHintTest {

    // -----------------------------------------------------------------------
    // Exact size values — pinned
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("MICRO sizeBytes() == 512")
    void microIs512() {
        assertThat(AllocationHint.MICRO.sizeBytes()).isEqualTo(512);
    }

    @Test
    @DisplayName("SMALL sizeBytes() == 4096")
    void smallIs4096() {
        assertThat(AllocationHint.SMALL.sizeBytes()).isEqualTo(4 * 1024);
    }

    @Test
    @DisplayName("MEDIUM sizeBytes() == 16384")
    void mediumIs16384() {
        assertThat(AllocationHint.MEDIUM.sizeBytes()).isEqualTo(16 * 1024);
    }

    @Test
    @DisplayName("NETWORK_FRAME sizeBytes() == 65536")
    void networkFrameIs65536() {
        assertThat(AllocationHint.NETWORK_FRAME.sizeBytes()).isEqualTo(64 * 1024);
    }

    @Test
    @DisplayName("STREAMING_CHUNK sizeBytes() == 32768")
    void streamingChunkIs32768() {
        assertThat(AllocationHint.STREAMING_CHUNK.sizeBytes()).isEqualTo(32 * 1024);
    }

    @Test
    @DisplayName("JUMBO sizeBytes() == 131072")
    void jumboIs131072() {
        assertThat(AllocationHint.JUMBO.sizeBytes()).isEqualTo(128 * 1024);
    }

    // -----------------------------------------------------------------------
    // Ordering invariants — sizes must be positive
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("All hints have positive sizeBytes() — no zero-size slabs")
    void allHintsPositive() {
        for (AllocationHint hint : AllocationHint.values()) {
            assertThat(hint.sizeBytes())
                    .as("%s.sizeBytes() must be positive", hint)
                    .isPositive();
        }
    }

    @Test
    @DisplayName("MICRO < SMALL < MEDIUM < STREAMING_CHUNK < NETWORK_FRAME < JUMBO")
    void sizesAreStrictlyIncreasing() {
        assertThat(AllocationHint.MICRO.sizeBytes())
                .isLessThan(AllocationHint.SMALL.sizeBytes());
        assertThat(AllocationHint.SMALL.sizeBytes())
                .isLessThan(AllocationHint.MEDIUM.sizeBytes());
        assertThat(AllocationHint.MEDIUM.sizeBytes())
                .isLessThan(AllocationHint.STREAMING_CHUNK.sizeBytes());
        assertThat(AllocationHint.STREAMING_CHUNK.sizeBytes())
                .isLessThan(AllocationHint.NETWORK_FRAME.sizeBytes());
        assertThat(AllocationHint.NETWORK_FRAME.sizeBytes())
                .isLessThan(AllocationHint.JUMBO.sizeBytes());
    }

    // -----------------------------------------------------------------------
    // Enum constant count — adding/removing hints requires review
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Total AllocationHint count == 6 — new hints require pool table review")
    void totalHintCount() {
        assertThat(AllocationHint.values()).hasSize(6);
    }

    // -----------------------------------------------------------------------
    // valueOf() stability — alias check
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("valueOf() resolves all canonical names without exception")
    void valueOfResolvesAllNames() {
        assertThat(AllocationHint.valueOf("MICRO")).isEqualTo(AllocationHint.MICRO);
        assertThat(AllocationHint.valueOf("SMALL")).isEqualTo(AllocationHint.SMALL);
        assertThat(AllocationHint.valueOf("MEDIUM")).isEqualTo(AllocationHint.MEDIUM);
        assertThat(AllocationHint.valueOf("NETWORK_FRAME")).isEqualTo(AllocationHint.NETWORK_FRAME);
        assertThat(AllocationHint.valueOf("STREAMING_CHUNK")).isEqualTo(AllocationHint.STREAMING_CHUNK);
        assertThat(AllocationHint.valueOf("JUMBO")).isEqualTo(AllocationHint.JUMBO);
    }
}

