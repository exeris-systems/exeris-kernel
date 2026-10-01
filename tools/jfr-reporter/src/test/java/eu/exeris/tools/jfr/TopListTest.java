/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.tools.jfr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The cap on a "top" list that had none. */
class TopListTest {

    @Test
    @DisplayName("the class histogram is capped and ordered by event count")
    void theClassHistogramIsCapped() {
        Map<String, ReportGenerator.Stats> byKey = new LinkedHashMap<>();
        for (int i = 0; i < 60; i++) {
            ReportGenerator.Stats stats = new ReportGenerator.Stats();
            stats.events = i;
            byKey.put("class" + i, stats);
        }

        List<Map.Entry<String, ReportGenerator.Stats>> top = ReportGenerator.rankTop(byKey, 50);

        assertThat(top)
                .as("alloc-top-classes.json is served by GitHub Pages; unbounded it is the whole histogram")
                .hasSize(50);
        assertThat(top.get(0).getKey()).isEqualTo("class59");
        assertThat(top.get(49).getKey()).isEqualTo("class10");
    }
}
