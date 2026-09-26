/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.memory;

import eu.exeris.kernel.community.transport.MapConfigProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The allocation-sampling stride must be reachable through {@code ConfigProvider}.
 *
 * <p>Until v0.12 it was read from {@code -Dexeris.community.memory.jfr.sampleEvery} and from
 * nothing else: not from a config file, not from the environment, and not from
 * {@code docs/subsystems/config.md}, which never listed it. The property remains the second tier,
 * because it was the published surface.
 */
@DisplayName("CommunityMemoryJfrSampling — the stride is configuration, not only a -D flag")
class CommunityMemoryJfrSamplingConfigTest {

    private static final String PROPERTY = "exeris.community.memory.jfr.sampleEvery";
    private static final int DEFAULT_SAMPLE_EVERY = 64;

    @AfterEach
    void clearProperty() {
        System.clearProperty(PROPERTY);
    }

    @Nested
    @DisplayName("resolved from the config provider")
    class FromConfig {

        @Test
        @DisplayName("a bound provider supplies the stride")
        void providerSuppliesStride() {
            MapConfigProvider config = MapConfigProvider.ofInts(Map.of("memory.jfr.sampleEvery", 8));
            int resolved = ScopedValue.where(KernelProviders.CURRENT_CONFIG, config)
                    .call(() -> CommunityMemoryJfrSampling.fromSystemProperties().sampleEvery());
            assertThat(resolved).isEqualTo(8);
        }

        @Test
        @DisplayName("the provider outranks the legacy -D, which is what makes it the primary source")
        void providerOutranksProperty() {
            System.setProperty(PROPERTY, "512");
            MapConfigProvider config = MapConfigProvider.ofInts(Map.of("memory.jfr.sampleEvery", 4));
            int resolved = ScopedValue.where(KernelProviders.CURRENT_CONFIG, config)
                    .call(() -> CommunityMemoryJfrSampling.fromSystemProperties().sampleEvery());
            assertThat(resolved)
                    .as("a provider value must win; otherwise the key is documented but powerless")
                    .isEqualTo(4);
        }

        @Test
        @DisplayName("a provider without the key falls through to the property, not to the default")
        void absentKeyFallsThroughToProperty() {
            System.setProperty(PROPERTY, "16");
            MapConfigProvider config = MapConfigProvider.ofInts(Map.of());
            int resolved = ScopedValue.where(KernelProviders.CURRENT_CONFIG, config)
                    .call(() -> CommunityMemoryJfrSampling.fromSystemProperties().sampleEvery());
            assertThat(resolved)
                    .as("the -D surface was published and must keep working when the key is absent")
                    .isEqualTo(16);
        }
    }

    @Nested
    @DisplayName("with no provider bound")
    class WithoutConfig {

        @Test
        @DisplayName("the legacy -D still resolves — a driver built outside a boot must still work")
        void propertyStillResolvesUnbound() {
            System.setProperty(PROPERTY, "32");
            assertThat(CommunityMemoryJfrSampling.fromSystemProperties().sampleEvery()).isEqualTo(32);
        }

        @Test
        @DisplayName("neither source present yields the documented default")
        void defaultsWhenNothingIsSet() {
            assertThat(CommunityMemoryJfrSampling.fromSystemProperties().sampleEvery())
                    .isEqualTo(DEFAULT_SAMPLE_EVERY);
        }
    }
}
