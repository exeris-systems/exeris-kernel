/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.json;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the ADR-052 mapper-assembly logic ({@link CommunityJsonMappers#apply}),
 * exercised against explicit customizer lists (no {@link java.util.ServiceLoader}).
 */
class CommunityJsonMappersTest {

    private record Sample(String name, int value) {
    }

    private static final Sample SAMPLE = new Sample("widget", 42);

    @ParameterizedTest
    @EnumSource(JsonMapperScope.class)
    void noCustomizerYieldsByteIdenticalDefaultMapper(JsonMapperScope scope) {
        byte[] viaSeam = CommunityJsonMappers.apply(scope, List.of()).writeValueAsBytes(SAMPLE);
        byte[] viaBareMapper = new ObjectMapper().writeValueAsBytes(SAMPLE);

        assertThat(viaSeam).isEqualTo(viaBareMapper);
    }

    @Test
    void applicableCustomizerConfiguresTheBuilder() {
        JsonMapperCustomizer indent = new FeatureCustomizer(0, s -> true, SerializationFeature.INDENT_OUTPUT, true);

        byte[] out = CommunityJsonMappers.apply(JsonMapperScope.HTTP_RESPONSE_ENCODE, List.of(indent))
                .writeValueAsBytes(SAMPLE);

        // INDENT_OUTPUT introduces a newline; the bare default mapper produces a single compact line.
        assertThat(new String(out)).contains("\n");
    }

    @Test
    void customizersApplyInAscendingOrderSoLaterWins() {
        // Lower order runs first; the higher-order customizer observes and overrides it.
        JsonMapperCustomizer first = new FeatureCustomizer(10, s -> true, SerializationFeature.INDENT_OUTPUT, false);
        JsonMapperCustomizer second = new FeatureCustomizer(20, s -> true, SerializationFeature.INDENT_OUTPUT, true);

        // Pass them out of order to prove the helper sorts by order(), not list order.
        byte[] out = CommunityJsonMappers.apply(JsonMapperScope.EVENTS, List.of(second, first))
                .writeValueAsBytes(SAMPLE);

        assertThat(new String(out)).contains("\n");
    }

    @Test
    void invocationOrderFollowsOrderValue() {
        List<Integer> log = new ArrayList<>();
        JsonMapperCustomizer high = new RecordingCustomizer(30, log);
        JsonMapperCustomizer low = new RecordingCustomizer(5, log);
        JsonMapperCustomizer mid = new RecordingCustomizer(15, log);

        CommunityJsonMappers.apply(JsonMapperScope.EVENTS, List.of(high, low, mid));

        assertThat(log).containsExactly(5, 15, 30);
    }

    @Test
    void nonApplicableScopeKeepsExactDefaultMapper() {
        // A customizer scoped only to HTTP_RESPONSE_ENCODE must not touch other scopes.
        JsonMapperCustomizer scoped = new FeatureCustomizer(
                0, s -> s == JsonMapperScope.HTTP_RESPONSE_ENCODE, SerializationFeature.INDENT_OUTPUT, true);

        byte[] untouched = CommunityJsonMappers.apply(JsonMapperScope.EVENTS, List.of(scoped))
                .writeValueAsBytes(SAMPLE);
        byte[] targeted = CommunityJsonMappers.apply(JsonMapperScope.HTTP_RESPONSE_ENCODE, List.of(scoped))
                .writeValueAsBytes(SAMPLE);

        assertThat(untouched).isEqualTo(new ObjectMapper().writeValueAsBytes(SAMPLE));
        assertThat(new String(targeted)).contains("\n");
    }

    // --- test doubles -----------------------------------------------------------------------

    private static final class FeatureCustomizer implements JsonMapperCustomizer {
        private final int order;
        private final java.util.function.Predicate<JsonMapperScope> applies;
        private final SerializationFeature feature;
        private final boolean enable;

        FeatureCustomizer(int order, java.util.function.Predicate<JsonMapperScope> applies,
                          SerializationFeature feature, boolean enable) {
            this.order = order;
            this.applies = applies;
            this.feature = feature;
            this.enable = enable;
        }

        @Override
        public boolean appliesTo(JsonMapperScope scope) {
            return applies.test(scope);
        }

        @Override
        public void customize(JsonMapperScope scope, JsonMapper.Builder builder) {
            builder.configure(feature, enable);
        }

        @Override
        public int order() {
            return order;
        }
    }

    private static final class RecordingCustomizer implements JsonMapperCustomizer {
        private final int order;
        private final List<Integer> log;

        RecordingCustomizer(int order, List<Integer> log) {
            this.order = order;
            this.log = log;
        }

        @Override
        public void customize(JsonMapperScope scope, JsonMapper.Builder builder) {
            log.add(order);
        }

        @Override
        public int order() {
            return order;
        }
    }
}
