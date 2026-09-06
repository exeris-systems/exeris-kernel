/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.bootstrap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * TCK: verifies the callback-registration contract of a dynamic configuration
 * change-notification registry. A callback registered for a {@code (file, key)} pair is
 * invoked with the new value on a matching {@link DynamicConfigRegistryAdapter#fireReload
 * fireReload}, is left untouched by a reload of a different key, does not propagate its own
 * thrown exception out of {@code fireReload}, and is rejected once the registry is sealed.
 */
public abstract class AbstractDynamicConfigRegistryTck {

    /**
     * Creates the registry adapter under test.
     *
     * @return a fresh {@link DynamicConfigRegistryAdapter} with no callbacks registered
     */
    protected abstract DynamicConfigRegistryAdapter createRegistry();

    /**
     * Adapts the registry under test to the {@code (register, seal, fireReload)} shape this
     * TCK drives; bindings implement it over their concrete registry type.
     */
    protected interface DynamicConfigRegistryAdapter {

        /**
         * Registers {@code callback} to be invoked with the new raw value whenever
         * {@link #fireReload} is called for the same {@code (file, key)} pair.
         *
         * @param file     config file name to match
         * @param key      dot-path key to match within {@code file}
         * @param callback invoked with the new raw string value on a matching reload
         */
        void register(String file, String key, java.util.function.Consumer<String> callback);

        /** Stops accepting new registrations; calls to {@link #register} after this are ignored. */
        void seal();

        /**
         * Notifies every callback registered for {@code (file, key)} with {@code value}.
         *
         * @param file  config file name to match against registered callbacks
         * @param key   dot-path key to match against registered callbacks
         * @param value new raw string value delivered to matching callbacks
         */
        void fireReload(String file, String key, String value);
    }

    @Test
    @DisplayName("matching file/key triggers callback with new value")
    void matchingRegistrationReceivesNewValue() {
        DynamicConfigRegistryAdapter registry = createRegistry();
        AtomicReference<String> value = new AtomicReference<>();

        registry.register("app.properties", "network.port", value::set);
        registry.fireReload("app.properties", "network.port", "9443");

        assertThat(value.get()).isEqualTo("9443");
    }

    @Test
    @DisplayName("non-matching key does not trigger callback")
    void nonMatchingKeyDoesNotTriggerCallback() {
        DynamicConfigRegistryAdapter registry = createRegistry();
        AtomicBoolean called = new AtomicBoolean(false);

        registry.register("app.properties", "network.port", ignored -> called.set(true));
        registry.fireReload("app.properties", "network.timeout", "100");

        assertThat(called.get()).isFalse();
    }

    /**
     * Establishes that a callback's own exception does not propagate out of
     * {@code fireReload}. The fixture registers exactly one callback for the matched key, so
     * this does not establish that a second callback registered for the same key still runs
     * after an earlier one throws — only that the throwing call site itself is contained.
     */
    @Test
    @DisplayName("callback exception is isolated and does not escape fireReload")
    void callbackFailureDoesNotEscape() {
        DynamicConfigRegistryAdapter registry = createRegistry();

        registry.register("app.properties", "network.port", ignored -> {
            throw new IllegalStateException("boom");
        });

        assertThatCode(() -> registry.fireReload("app.properties", "network.port", "9443"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("register after seal is ignored")
    void registerAfterSealIsIgnored() {
        DynamicConfigRegistryAdapter registry = createRegistry();
        AtomicBoolean called = new AtomicBoolean(false);

        registry.seal();
        registry.register("app.properties", "network.port", ignored -> called.set(true));
        registry.fireReload("app.properties", "network.port", "9443");

        assertThat(called.get()).isFalse();
    }
}

