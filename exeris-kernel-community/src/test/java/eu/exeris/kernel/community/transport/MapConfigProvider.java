/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.spi.config.ConfigProvider;

import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Test support: a {@link ConfigProvider} backed by two literal maps.
 *
 * <p>Bootstrap resolution is read-once and typed per key, so the tests that assert which keys a
 * boot path honours need nothing more than this. Test scope only — the testkit module is published,
 * and a stub this narrow does not belong on a consumer-facing surface.
 *
 * @since 0.12.0
 */
public final class MapConfigProvider implements ConfigProvider {

    private final Map<String, String> strings;
    private final Map<String, Integer> ints;

    public MapConfigProvider(Map<String, String> strings, Map<String, Integer> ints) {
        this.strings = Map.copyOf(strings);
        this.ints = Map.copyOf(ints);
    }

    public static MapConfigProvider ofInts(Map<String, Integer> ints) {
        return new MapConfigProvider(Map.of(), ints);
    }

    @Override
    public Supplier<KernelSettings> kernelSettings() {
        return KernelSettings::defaults;
    }

    @Override
    public Optional<String> getString(String key) {
        return Optional.ofNullable(strings.get(key));
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
        // no-op — every key these tests cover is read once at bootstrap
    }
}
