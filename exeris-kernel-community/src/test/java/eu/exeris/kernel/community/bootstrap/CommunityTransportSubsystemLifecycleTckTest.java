/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
 * Community binding: verifies {@link CommunityTransportSubsystem} satisfies
 * {@link AbstractSubsystemLifecycleTck} contract in DISABLED mode.
 *
 * <p>{@code KernelProviders.CURRENT_CONFIG} is bound via {@link #withLifecycleContext(Runnable)}
 * using a minimal stub that returns {@link Optional#empty()} for all keys.
 * {@code transport.mode} and {@code network.transportMode} therefore both resolve to
 * {@code null} in {@code CommunityTransportSubsystem.resolveMode()}, yielding
 * {@code TransportMode.DISABLED} and avoiding any engine creation or network binding.
 */
@DisplayName("Community: CommunityTransportSubsystem lifecycle TCK")
class CommunityTransportSubsystemLifecycleTckTest extends AbstractSubsystemLifecycleTck {

    @Override
    protected Subsystem createSubsystem() {
        return new CommunityTransportSubsystem();
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
