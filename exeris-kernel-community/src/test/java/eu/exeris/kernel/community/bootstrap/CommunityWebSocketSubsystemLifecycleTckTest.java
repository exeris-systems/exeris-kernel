/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.spi.bootstrap.Subsystem;
import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.tck.contract.bootstrap.AbstractSubsystemLifecycleTck;
import org.junit.jupiter.api.DisplayName;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Binds the subsystem lifecycle contract to {@link CommunityWebSocketSubsystem}.
 *
 * <p>Disabled configuration, deliberately, and for the same reason the HTTP binding uses
 * {@code DISABLED} mode: the contract under test is {@code initialize → start → stop → isRunning},
 * not whether a socket binds, and a lifecycle suite that opened a listener would be a network test
 * wearing a contract test's name.
 */
@DisplayName("Community: CommunityWebSocketSubsystem lifecycle TCK")
class CommunityWebSocketSubsystemLifecycleTckTest extends AbstractSubsystemLifecycleTck {

    @Override
    protected Subsystem createSubsystem() {
        return new CommunityWebSocketSubsystem();
    }

    @Override
    protected void withLifecycleContext(Runnable action) {
        ScopedValue.where(KernelProviders.CURRENT_CONFIG, new DisabledConfigProvider())
                .run(action);
    }

    private static final class DisabledConfigProvider implements ConfigProvider {

        @Override
        public Supplier<ConfigProvider.KernelSettings> kernelSettings() {
            return ConfigProvider.KernelSettings::defaults;
        }

        @Override
        public Optional<String> getString(String key) {
            return Optional.empty();
        }

        @Override
        public Optional<Integer> getInt(String key) {
            return Optional.empty();
        }

        @Override
        public Optional<Long> getLong(String key) {
            return Optional.empty();
        }

        @Override
        public Optional<Boolean> getBoolean(String key) {
            // The subsystem's own gate. Absent would also mean disabled, but saying it explicitly
            // keeps this binding honest about which state it is exercising.
            if ("websocket.enabled".equals(key)) {
                return Optional.of(false);
            }
            return Optional.empty();
        }

        @Override
        public <T> Optional<T> get(String key, Class<T> type) {
            return Optional.empty();
        }

        @Override
        public void watch(String file, String key, Consumer<Object> callback) {
            // no-op
        }
    }
}
