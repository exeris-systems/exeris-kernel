/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.memory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L0 Contract: {@link LeakDetectionMode} — ordinal stability.
 *
 * <h2>Why Ordinals Matter</h2>
 * <p>The Enterprise memory allocator encodes the leak detection mode as a raw bit
 * in its off-heap configuration header. Reordering these constants silently selects
 * the wrong detection mode without any runtime error.
 *
 * @since 0.5.0
 */
@DisplayName("L0: LeakDetectionMode — Ordinal Stability")
class LeakDetectionModeTest {

    @Test
    @DisplayName("DISABLED ordinal == 0 — production default, zero overhead")
    void disabledOrdinal() {
        assertThat(LeakDetectionMode.DISABLED.ordinal()).isZero();
    }

    @Test
    @DisplayName("SAMPLED ordinal == 1 — canary/staging mode")
    void sampledOrdinal() {
        assertThat(LeakDetectionMode.SAMPLED.ordinal()).isEqualTo(1);
    }

    @Test
    @DisplayName("PARANOID ordinal == 2 — full tracking, test-only mode")
    void paranoidOrdinal() {
        assertThat(LeakDetectionMode.PARANOID.ordinal()).isEqualTo(2);
    }

    @Test
    @DisplayName("Total count == 3 — adding a new mode requires off-heap config header review")
    void totalCount() {
        assertThat(LeakDetectionMode.values()).hasSize(3);
    }

    @Test
    @DisplayName("valueOf() resolves all canonical names")
    void valueOfResolvesAll() {
        assertThat(LeakDetectionMode.valueOf("DISABLED")).isEqualTo(LeakDetectionMode.DISABLED);
        assertThat(LeakDetectionMode.valueOf("SAMPLED")).isEqualTo(LeakDetectionMode.SAMPLED);
        assertThat(LeakDetectionMode.valueOf("PARANOID")).isEqualTo(LeakDetectionMode.PARANOID);
    }
}
