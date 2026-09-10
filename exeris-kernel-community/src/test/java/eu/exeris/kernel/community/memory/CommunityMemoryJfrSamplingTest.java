/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.memory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("L1 Unit: Community memory JFR sampling")
class CommunityMemoryJfrSamplingTest {

    private static final String SAMPLE_EVERY_PROPERTY = "exeris.community.memory.jfr.sampleEvery";

    @AfterEach
    @SuppressWarnings("unused")
    void clearProperty() {
        System.clearProperty(SAMPLE_EVERY_PROPERTY);
    }

    @Test
    @DisplayName("default sampleEvery is 64")
    void defaultSampleEveryIs64() {
        CommunityMemoryJfrSampling sampling = CommunityMemoryJfrSampling.fromSystemProperties();
        assertThat(sampling.sampleEvery()).isEqualTo(64);
    }

    @Test
    @DisplayName("sampleEvery=1 emits all events")
    void sampleEveryOneEmitsAllEvents() {
        System.setProperty(SAMPLE_EVERY_PROPERTY, "1");
        CommunityMemoryJfrSampling sampling = CommunityMemoryJfrSampling.fromSystemProperties();

        assertThat(sampling.shouldEmit(1)).isTrue();
        assertThat(sampling.shouldEmit(2)).isTrue();
        assertThat(sampling.shouldEmit(3)).isTrue();
    }

    @Test
    @DisplayName("sampleEvery=8 emits every eighth event")
    void emitsEveryNthEvent() {
        System.setProperty(SAMPLE_EVERY_PROPERTY, "8");
        CommunityMemoryJfrSampling sampling = CommunityMemoryJfrSampling.fromSystemProperties();

        assertThat(sampling.shouldEmit(7)).isFalse();
        assertThat(sampling.shouldEmit(8)).isTrue();
        assertThat(sampling.shouldEmit(9)).isFalse();
        assertThat(sampling.shouldEmit(16)).isTrue();
    }

    @Test
    @DisplayName("invalid values fall back to safe defaults")
    void invalidValuesFallbackSafely() {
        System.setProperty(SAMPLE_EVERY_PROPERTY, "not-a-number");
        CommunityMemoryJfrSampling malformed = CommunityMemoryJfrSampling.fromSystemProperties();
        assertThat(malformed.sampleEvery()).isEqualTo(64);

        System.setProperty(SAMPLE_EVERY_PROPERTY, "0");
        CommunityMemoryJfrSampling zero = CommunityMemoryJfrSampling.fromSystemProperties();
        assertThat(zero.sampleEvery()).isEqualTo(1);

        System.setProperty(SAMPLE_EVERY_PROPERTY, "-10");
        CommunityMemoryJfrSampling negative = CommunityMemoryJfrSampling.fromSystemProperties();
        assertThat(negative.sampleEvery()).isEqualTo(1);
    }
}
