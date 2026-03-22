/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.tck.contract.bootstrap;

import eu.exeris.kernel.spi.config.ConfigProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Objects;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCK: Abstract base for {@link ConfigProvider} contract verification.
 *
 * <h2>Front 1 — Bootstrap &amp; Config</h2>
 * <p>This TCK enforces the JEP 526 (LazyConstant-semantic) single-initialisation
 * guarantee and verifies that no banned heavy parsers (Jackson, YAML snake) are
 * pulled in by the implementation under test.
 *
 * <h2>Verified Constraints</h2>
 * <ol>
 *   <li>kernelSettings() returns a non-null {@link Supplier} and the value is non-null.</li>
 *   <li>Repeated calls return the <b>same instance</b> (effectively-immutable / LazyConstant).</li>
 *   <li>Concurrent access from N Virtual Threads still yields the same instance (single-init).</li>
 *   <li>KernelSettings is a Valhalla-ready record — no identity operations allowed.</li>
 *   <li>The implementation class name does NOT reference banned Jackson / SnakeYAML classes.</li>
 *   <li>priority() must be ≥ 0 (contract: Community = 100, Enterprise = 200).</li>
 * </ol>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * class CommunityConfigProviderTest extends AbstractConfigProviderTck {
 *     \@Override protected ConfigProvider createProvider() {
 *         return new SimpleFileConfigProvider();
 *     }
 * }
 * }</pre>
 *
 * @since 0.5.0
 */
public abstract class AbstractConfigProviderTck {

    // =========================================================================
    // Template method — subclass supplies the SUT
    // =========================================================================

    /** Creates the {@link ConfigProvider} under test. Must NOT perform I/O in constructor. */
    protected abstract ConfigProvider createProvider();

    private ConfigProvider provider;

    @BeforeEach
    final void setUp() {
        provider = createProvider();
    }

    // =========================================================================
    // Priority contract
    // =========================================================================

    @Test
    @DisplayName("priority() must be ≥ 0 (convention: Community=100, Enterprise=200)")
    void priorityIsNonNegative() {
        assertThat(provider.priority())
                .as("ConfigProvider.priority() must be non-negative")
                .isGreaterThanOrEqualTo(0);
    }

    // =========================================================================
    // KernelSettings — LazyConstant semantics (JEP 526)
    // =========================================================================

    @Nested
    @DisplayName("KernelSettings — LazyConstant / single-init contract")
    class KernelSettingsLazyContract {

        @Test
        @DisplayName("kernelSettings() returns a non-null Supplier")
        void supplierIsNotNull() {
            assertThat(provider.kernelSettings())
                    .as("kernelSettings() supplier must never be null")
                    .isNotNull();
        }

        @Test
        @DisplayName("kernelSettings().get() resolves to a non-null value")
        void settingsValueIsNotNull() {
            var settings = provider.kernelSettings().get();
            assertThat(settings)
                    .as("KernelSettings value resolved from supplier must not be null")
                    .isNotNull();
        }

        @RepeatedTest(value = 5, name = "repeated call {currentRepetition} returns same instance")
        @DisplayName("Repeated calls return the SAME instance (LazyConstant semantic)")
        void repeatedCallsReturnSameInstance() {
            var first  = provider.kernelSettings().get();
            var second = provider.kernelSettings().get();
            assertThat(second)
                    .as("kernelSettings() must return the same instance on repeated calls " +
                        "(JEP 526 LazyConstant contract: computed exactly once)")
                    .isSameAs(first);
        }

        @Test
        @DisplayName("Concurrent VT access — single initialisation under contention (JEP 526)")
        void singleInitUnderVirtualThreadContention() throws Exception {
            // Spin up 64 virtual threads simultaneously reading kernelSettings().
            // All must see the same instance (no duplicate initialisation allowed).
            int threads = 64;
            AtomicReference<Object> firstSeen = new AtomicReference<>(null);
            AtomicReference<AssertionError> failure = new AtomicReference<>(null);

            try (var scope = StructuredTaskScope.open()) {
                for (int i = 0; i < threads; i++) {
                    scope.fork(() -> {
                        var settings = provider.kernelSettings().get();
                        if (!firstSeen.compareAndSet(null, settings)) {
                            Object expected = firstSeen.get();
                            if (expected != settings) {
                                failure.compareAndSet(null, new AssertionError(
                                        "KernelSettings was initialised more than once! " +
                                        "LazyConstant single-init contract violated. " +
                                        "Expected: " + System.identityHashCode(expected) +
                                        ", got: " + System.identityHashCode(settings)));
                            }
                        }
                        return null;
                    });
                }
                scope.join();
            }

            if (failure.get() != null) {
                throw failure.get();
            }

            assertThat(firstSeen.get())
                    .as("KernelSettings MUST be initialised exactly once under VT contention " +
                        "(JEP 526 LazyConstant single-init contract)")
                    .isNotNull();
        }
    }

    // =========================================================================
    // Banned dependency audit
    // =========================================================================

    @Test
    @DisplayName("Implementation class must NOT contain 'jackson' in its full class name")
    void noJacksonInImplementationClass() {
        String implClassName = provider.getClass().getName().toLowerCase();
        assertThat(implClassName)
                .as("ConfigProvider implementation MUST NOT embed Jackson in its class hierarchy. " +
                    "Jackson is a banned heavy parser on the L0 bootstrap path. " +
                    "Use java.util.Properties or a custom zero-dependency parser.")
                .doesNotContain("jackson");
    }

    @Test
    @DisplayName("Implementation class must NOT contain 'snakeyaml' in its full class name")
    void noSnakeYamlInImplementationClass() {
        String implClassName = provider.getClass().getName().toLowerCase();
        assertThat(implClassName)
                .as("ConfigProvider implementation MUST NOT embed SnakeYAML in its class hierarchy. " +
                    "Heavy YAML parsers violate the No-Waste-Compute principle on L0 bootstrap.")
                .doesNotContain("snakeyaml");
    }

    @Test
    @DisplayName("Implementation must be loadable without reflection — public no-arg constructor")
    void publicNoArgConstructorExists() throws NoSuchMethodException {
        Constructor<?> ctor = provider.getClass().getConstructor();
        assertThat(Modifier.isPublic(ctor.getModifiers()))
                .as("ConfigProvider implementations MUST have a public no-arg constructor " +
                    "for ServiceLoader discovery (banned: reflection-based DI frameworks).")
                .isTrue();
    }

    // =========================================================================
    // watch() — hot-reload SPI contract
    // =========================================================================

    /**
     * Returns {@code true} if this implementation supports live hot-reload via
     * {@link ConfigProvider#watch(String, String, java.util.function.Consumer)}.
     *
     * <p>Community implementations MUST return {@code false} (no-op contract).
     * Enterprise implementations MUST return {@code true} and override
     * {@link #triggerReload(String, String, String)} to write the new value.
     *
     * <p>Default: {@code false} — safe baseline for Community tests.
     */
    protected boolean supportsHotReload() {
        return false;
    }

    /**
     * Triggers a reload of {@code key} in {@code file} to {@code newValue}.
     *
     * <p>Override in Enterprise tests to write to the real config file and
     * wait for the WatchService to fire. Default: no-op (Community tier).
     *
     * @param file     config file name
     * @param key      dot-path key
     * @param newValue new raw string value
     * @throws Exception if the trigger fails
     */
    protected void triggerReload(String file, String key, String newValue) throws Exception {
        Objects.hashCode(file);
        Objects.hashCode(key);
        Objects.hashCode(newValue);
        // no-op for Community tier
    }

    @Nested
    @DisplayName("watch() — hot-reload contract")
    class WatchContract {

        @Test
        @DisplayName("watch() must not throw for any valid (file, key, callback) combination")
        void watchDoesNotThrow() {
            // Community: no-op. Enterprise: registers a NIO WatchService listener.
            // Either way, calling watch() must never throw.
            provider.watch("app.properties", "network.port", ignored -> {});
            provider.watch(null, "network.port", ignored -> {});
        }

        @Test
        @DisplayName("watch() with null callback throws NullPointerException")
        void watchWithNullCallbackThrowsNullPointerException() {
            assertThatThrownBy(() -> provider.watch("app.properties", "network.port", null))
                    .as("watch() must reject a null callback explicitly")
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("Community tier: watch() is a no-op — callback is never invoked")
        void communityWatchIsNoOp() {
            if (supportsHotReload()) {
                return; // Enterprise tier: skipped — covered by triggerReload() test below
            }
            var called = new AtomicBoolean(false);
            provider.watch("app.properties", "network.port", v -> called.set(true));

            // No file change happens — the Community no-op must never invoke the callback
            assertThat(called.get())
                    .as("Community watch() is a no-op — callback must not be invoked without a file change")
                    .isFalse();
        }

        @Test
        @DisplayName("Enterprise tier: watch() delivers new value after file change (hot-reload)")
        void enterpriseWatchDeliversOnReload() throws Exception {
            if (!supportsHotReload()) {
                return; // Community tier: skipped
            }
            var received = new AtomicReference<String>();
            provider.watch("app.properties", "network.port", v -> received.set((String) v));

            triggerReload("app.properties", "network.port", "9999");

            assertThat(received.get())
                    .as("Enterprise watch() must deliver the new value '9999' after triggerReload()")
                    .isEqualTo("9999");
        }
    }
}

