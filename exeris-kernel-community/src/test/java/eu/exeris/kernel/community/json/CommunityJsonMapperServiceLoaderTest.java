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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the ADR-052 seam wires {@link java.util.ServiceLoader} discovery correctly: the
 * {@link NoopJsonMapperCustomizer} registered on the test classpath (see
 * {@code src/test/resources/META-INF/services/eu.exeris.kernel.community.json.JsonMapperCustomizer})
 * is found by {@link CommunityJsonMappers}.
 *
 * <p>The test customizer's {@link JsonMapperCustomizer#appliesTo(JsonMapperScope)} returns
 * {@code false} for every scope, so it is discovered but never applied — mappers produced during
 * the module's test run stay byte-for-byte default and no other test is perturbed.
 */
class CommunityJsonMapperServiceLoaderTest {

    @Test
    void registeredCustomizerIsDiscoveredViaServiceLoader() {
        assertThat(CommunityJsonMappers.discovered())
                .anySatisfy(customizer -> assertThat(customizer).isInstanceOf(NoopJsonMapperCustomizer.class));
    }

    @Test
    void discoveredButNonApplicableCustomizerLeavesMappersAtDefault() {
        // forScope goes through the real discovered list; the noop applies to nothing, so the
        // provider mappers stay identical to the bare default.
        var viaSeam = CommunityJsonMappers.forScope(JsonMapperScope.HTTP_RESPONSE_ENCODE)
                .writeValueAsBytes(java.util.Map.of("k", "v"));
        var viaBare = new tools.jackson.databind.ObjectMapper()
                .writeValueAsBytes(java.util.Map.of("k", "v"));

        assertThat(viaSeam).isEqualTo(viaBare);
    }
}
