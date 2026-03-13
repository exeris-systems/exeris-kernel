/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.tck.contract.bootstrap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * TCK contract for dynamic config callback registry semantics.
 */
public abstract class AbstractDynamicConfigRegistryTck {

    protected abstract DynamicConfigRegistryAdapter createRegistry();

    protected interface DynamicConfigRegistryAdapter {
        void register(String file, String key, java.util.function.Consumer<String> callback);

        void seal();

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

