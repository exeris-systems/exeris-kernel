/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.spi.config.ConfigProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Community reactor count resolver")
class CommunityReactorCountResolverTest {

    @Test
    @DisplayName("explicit transport.reactorCount wins")
    void explicitTransportReactorCountWins() {
        ConfigProvider configProvider = mapConfig(Map.of(
                "transport.reactorCount", 12,
                "network.reactorCount", 8,
                "transport.auto.reserveCores", 4
        ));

        assertThat(CommunityReactorCountResolver.resolve(configProvider, 32)).isEqualTo(12);
    }

    @Test
    @DisplayName("network.reactorCount is used when transport.reactorCount is missing")
    void networkReactorCountUsedWhenTransportMissing() {
        ConfigProvider configProvider = mapConfig(Map.of("network.reactorCount", 7));

        assertThat(CommunityReactorCountResolver.resolve(configProvider, 32)).isEqualTo(7);
    }

    @Test
    @DisplayName("auto default with 32 CPUs returns 30")
    void autoDefaultReservesTwoCoresOnLargeHosts() {
        ConfigProvider configProvider = mapConfig(Map.of());

        assertThat(CommunityReactorCountResolver.resolve(configProvider, 32)).isEqualTo(30);
    }

    @Test
    @DisplayName("reserve cores reduces candidate")
    void reserveCoresReducesCandidate() {
        ConfigProvider configProvider = mapConfig(Map.of("transport.auto.reserveCores", 6));

        assertThat(CommunityReactorCountResolver.resolve(configProvider, 16)).isEqualTo(10);
    }

    @Test
    @DisplayName("min and max auto bounds clamp result")
    void minAndMaxBoundsClampResult() {
        ConfigProvider highCandidate = mapConfig(Map.of(
                "transport.auto.reserveCores", 0,
                "transport.auto.minReactors", 2,
                "transport.auto.maxReactors", 4
        ));
        ConfigProvider lowCandidate = mapConfig(Map.of(
                "transport.auto.reserveCores", 15,
                "transport.auto.minReactors", 2,
                "transport.auto.maxReactors", 4
        ));

        assertThat(CommunityReactorCountResolver.resolve(highCandidate, 16)).isEqualTo(4);
        assertThat(CommunityReactorCountResolver.resolve(lowCandidate, 16)).isEqualTo(2);
    }

    @Test
    @DisplayName("maxReactors less or equal zero means uncapped to CPU")
    void nonPositiveMaxMeansUncappedToCpu() {
        ConfigProvider configProvider = mapConfig(Map.of(
                "transport.auto.reserveCores", 2,
                "transport.auto.minReactors", 1,
                "transport.auto.maxReactors", 0
        ));

        assertThat(CommunityReactorCountResolver.resolve(configProvider, 12)).isEqualTo(10);
    }

    @Test
    @DisplayName("null configProvider falls back to cpu-based default")
    void nullConfigFallsBackToCpuBasedDefault() {
        assertThat(CommunityReactorCountResolver.resolve(null, 32)).isEqualTo(30);
        assertThat(CommunityReactorCountResolver.resolve(null, 0)).isEqualTo(1);
    }

    private static ConfigProvider mapConfig(Map<String, Integer> ints) {
        return new ConfigProvider() {
            @Override
            public Supplier<KernelSettings> kernelSettings() {
                return KernelSettings::defaults;
            }

            @Override
            public Optional<String> getString(String key) {
                return Optional.empty();
            }

            @Override
            public Optional<Integer> getInt(String key) {
                return Optional.ofNullable(ints.get(key));
            }

            @Override
            public Optional<Long> getLong(String key) {
                return Optional.empty();
            }

            @Override
            public Optional<Boolean> getBoolean(String key) {
                return Optional.empty();
            }

            @Override
            public <T> Optional<T> get(String key, Class<T> type) {
                return Optional.empty();
            }

            @Override
            public void watch(String file, String key, Consumer<Object> callback) {
                // no-op for tests
            }
        };
    }
}
