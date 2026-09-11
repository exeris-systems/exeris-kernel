/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.tools.jfr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SizePolicyTest {

    @Test
    @DisplayName("a sample is worth its weight, never an object size")
    void sampleUsesWeight() {
        assertThat(SizePolicy.sizeOf(SizePolicy.SAMPLE, 0L, 262_144L, 0L))
                .isEqualTo(new SizePolicy.Size(262_144L, SizePolicy.SizeKind.SAMPLE_WEIGHT));
    }

    @Test
    @DisplayName("a TLAB refill is worth the TLAB, not the one object that did not fit")
    void inNewTlabUsesTlabSize() {
        assertThat(SizePolicy.sizeOf(SizePolicy.IN_NEW_TLAB, 24L, 0L, 131_072L))
                .isEqualTo(new SizePolicy.Size(131_072L, SizePolicy.SizeKind.TLAB_SIZE));
    }

    @Test
    @DisplayName("an outside-TLAB allocation is exact")
    void outsideTlabIsExact() {
        assertThat(SizePolicy.sizeOf(SizePolicy.OUTSIDE_TLAB, 2_097_168L, 0L, 0L))
                .isEqualTo(new SizePolicy.Size(2_097_168L, SizePolicy.SizeKind.EXACT));
    }

    @Test
    void unknownTypeHasNoFigure() {
        assertThat(SizePolicy.sizeOf("jdk.GCHeapSummary", 1L, 2L, 3L).kind())
                .isEqualTo(SizePolicy.SizeKind.UNKNOWN);
    }
}
