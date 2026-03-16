/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.bootstrap;

import eu.exeris.kernel.spi.bootstrap.BootstrapSelector;
import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Core unit tests for {@link KernelBootstrap} — verifies ScopedValue binding,
 * builder API, and boot/shutdown lifecycle.
 *
 * <h2>Location rationale</h2>
 * <p>{@link KernelBootstrap} is the "Ignition Switch" of {@code exeris-kernel-core}.
 * Its unit tests must live here, not in {@code exeris-kernel-community}. Community
 * has no business testing Core's orchestration logic.
 *
 * <h2>ConfigProvider shim</h2>
 * <p>{@code boot()} discovers a {@link ConfigProvider} via {@link java.util.ServiceLoader}.
 * Because Core must not depend on Community, we inject a minimal in-process
 * {@link StubConfigProvider} through a {@link DualServiceShimLoader} that overrides
 * the {@code META-INF/services} resource stream — zero filesystem I/O, zero external deps.
 *
 * @since 0.5.0
 */
@DisplayName("KernelBootstrap — Core unit tests")
class KernelBootstrapTest {

    private static final String WATCH_SUBSYSTEM_NAME = "config-watch-test";
    private static final AtomicReference<ConfigWatchProbe> WATCH_PROBE_REF = new AtomicReference<>();

    // =========================================================================
    // StubConfigProvider — in-process ConfigProvider for Core tests
    //
    // Must be public static with a public no-arg constructor to satisfy ServiceLoader.
    // =========================================================================

    /**
     * Minimal in-process {@link ConfigProvider} for Core tests.
     * No file I/O, no external deps, no Jackson, no SLF4J.
     */
    public static final class StubConfigProvider implements ConfigProvider {

        /** Required public no-arg constructor for {@link java.util.ServiceLoader}. */
        public StubConfigProvider() {
            // ServiceLoader-mandated no-op constructor.
        }

        @Override
        public Supplier<KernelSettings> kernelSettings() {
            return KernelSettings::defaults;
        }

        @Override public Optional<String> getString(String key)   { return Optional.empty(); }
        @Override public Optional<Integer> getInt(String key)     { return Optional.empty(); }
        @Override public Optional<Long> getLong(String key)       { return Optional.empty(); }
        @Override public Optional<Boolean> getBoolean(String key) { return Optional.empty(); }

        @Override
        public <T> Optional<T> get(String key, Class<T> type) { return Optional.empty(); }

        @Override
        public void watch(String file, String key, Consumer<Object> callback) {
            // no hot-reload in stub
        }

        @Override
        public int priority() { return 100; }

        @Override
        public String providerName() { return "StubConfigProvider"; }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Builds a {@link KernelBootstrap} with:
     * <ul>
     *   <li>{@link BootstrapSelector#none()} — zero subsystems booted (fast test)</li>
     *   <li>{@link DualServiceShimLoader} injecting {@link StubConfigProvider}</li>
     * </ul>
     */
    private static KernelBootstrap bootstrapWithStubConfig() {
        ClassLoader shimLoader = new DualServiceShimLoader(
                Thread.currentThread().getContextClassLoader(),
                Map.of(
                        "eu.exeris.kernel.spi.config.ConfigProvider",
                        StubConfigProvider.class.getName()
                ));

        return KernelBootstrap.builder()
                .selector(BootstrapSelector.none())
                .classLoader(shimLoader)
                .build();
    }

    // =========================================================================
    // Test suites
    // =========================================================================

    @Nested
    @DisplayName("ScopedValue — CURRENT_CONFIG binding")
    class CurrentConfigBinding {

        @Test
        @DisplayName("CURRENT_CONFIG is bound and non-null inside boot() lambda")
        void configBoundInsideLambda() throws KernelBootstrap.BootstrapException {
            AtomicReference<ConfigProvider> captured = new AtomicReference<>();

            bootstrapWithStubConfig()
                    .boot(() -> captured.set(KernelProviders.CURRENT_CONFIG.get()));

            assertThat(captured.get())
                    .as("CURRENT_CONFIG must be non-null inside boot() lambda")
                    .isNotNull();
        }

        @Test
        @DisplayName("CURRENT_CONFIG is unbound outside the boot() lambda — The Wall holds")
        void configUnboundOutsideLambda() throws KernelBootstrap.BootstrapException {
            bootstrapWithStubConfig().boot(() -> { /* no-op — scope is the subject */ });

            assertThat(KernelProviders.CURRENT_CONFIG.isBound())
                    .as("CURRENT_CONFIG must NOT be bound outside the boot() scope")
                    .isFalse();
        }

        @Test
        @DisplayName("Config profile is readable inside boot() lambda")
        void configProfileIsReadable() throws KernelBootstrap.BootstrapException {
            AtomicReference<String> profile = new AtomicReference<>();

            bootstrapWithStubConfig().boot(() -> {
                ConfigProvider cfg = KernelProviders.CURRENT_CONFIG.get();
                profile.set(cfg.kernelSettings().get().profile().toString());
            });

            assertThat(profile.get())
                    .as("profile must be non-null and non-blank")
                    .isNotNull()
                    .isNotBlank();
        }

        @Test
        @DisplayName("Config providerName matches the injected stub")
        void configProviderNameMatchesStub() throws KernelBootstrap.BootstrapException {
            AtomicReference<String> providerName = new AtomicReference<>();

            bootstrapWithStubConfig()
                    .boot(() -> providerName.set(
                            KernelProviders.CURRENT_CONFIG.get().providerName()));

            assertThat(providerName.get()).isEqualTo("StubConfigProvider");
        }
    }

    @Nested
    @DisplayName("Boot lifecycle")
    class BootLifecycle {

        @Test
        @DisplayName("kernelMain runnable is always executed inside boot()")
        void kernelMainIsExecuted() throws KernelBootstrap.BootstrapException {
            AtomicBoolean ran = new AtomicBoolean(false);

            bootstrapWithStubConfig().boot(() -> ran.set(true));

            assertThat(ran.get()).isTrue();
        }

        @Test
        @DisplayName("DEGRADE failure policy boots cleanly with zero subsystems")
        void degradePolicyBootsWithNoSubsystems() throws KernelBootstrap.BootstrapException {
            AtomicBoolean ran = new AtomicBoolean(false);

            ClassLoader shimLoader = new DualServiceShimLoader(
                    Thread.currentThread().getContextClassLoader(),
                    Map.of("eu.exeris.kernel.spi.config.ConfigProvider",
                            StubConfigProvider.class.getName()));

            KernelBootstrap.builder()
                    .selector(BootstrapSelector.none())
                    .failurePolicy(SubsystemOrchestrator.FailurePolicy.DEGRADE)
                    .classLoader(shimLoader)
                    .build()
                    .boot(() -> ran.set(true));

            assertThat(ran.get()).isTrue();
        }

        @Test
        @DisplayName("ConfigProvider.watch from initialize() receives runtime.properties reload")
        void configWatchFromInitializeReceivesFileReload() throws Exception {
            Path tempConfigDir = Files.createTempDirectory("exeris-kernel-watch-");
            Path runtimeProperties = tempConfigDir.resolve("runtime.properties");
            Files.writeString(runtimeProperties, "dynamic.value=before\n", StandardCharsets.UTF_8);

            String previousConfigDir = System.getProperty("exeris.config.dir");
            System.setProperty("exeris.config.dir", tempConfigDir.toString());

            ConfigWatchProbe probe = new ConfigWatchProbe();
            WATCH_PROBE_REF.set(probe);
            AtomicBoolean callbackObservedInKernelMain = new AtomicBoolean(false);

            ClassLoader shimLoader = new DualServiceShimLoader(
                    Thread.currentThread().getContextClassLoader(),
                    Map.of(
                            "eu.exeris.kernel.spi.config.ConfigProvider",
                            StubConfigProvider.class.getName(),
                            "eu.exeris.kernel.spi.bootstrap.SubsystemProvider",
                            WatchTestSubsystemProvider.class.getName()
                    ));

            try {
                KernelBootstrap.builder()
                        .selector(BootstrapSelector.forNames(WATCH_SUBSYSTEM_NAME))
                        .classLoader(shimLoader)
                        .build()
                        .boot(() -> {
                            try {
                                Files.writeString(runtimeProperties,
                                        "dynamic.value=after\n",
                                        StandardCharsets.UTF_8);
                                callbackObservedInKernelMain.set(probe.latch().await(3, TimeUnit.SECONDS));
                            } catch (IOException ex) {
                                throw new RuntimeException(ex);
                            } catch (InterruptedException ex) {
                                Thread.currentThread().interrupt();
                                throw new RuntimeException(ex);
                            }
                        });

                assertThat(callbackObservedInKernelMain.get())
                        .as("watch callback should fire after runtime.properties update")
                        .isTrue();
                assertThat(probe.value().get()).isEqualTo("after");
            } finally {
                WATCH_PROBE_REF.set(null);
                if (previousConfigDir == null) {
                    System.clearProperty("exeris.config.dir");
                } else {
                    System.setProperty("exeris.config.dir", previousConfigDir);
                }
                Files.deleteIfExists(runtimeProperties);
                Files.deleteIfExists(tempConfigDir);
            }
        }
    }

    public static final class WatchTestSubsystemProvider implements eu.exeris.kernel.spi.bootstrap.SubsystemProvider {

        public WatchTestSubsystemProvider() {
            // ServiceLoader-mandated no-op constructor.
        }

        @Override
        public List<eu.exeris.kernel.spi.bootstrap.Subsystem> getSubsystems(ConfigProvider config) {
            return List.of(new WatchTestSubsystem());
        }
    }

    private static final class WatchTestSubsystem implements eu.exeris.kernel.spi.bootstrap.Subsystem {

        @Override
        public String name() {
            return WATCH_SUBSYSTEM_NAME;
        }

        @Override
        public List<String> dependsOn() {
            return List.of();
        }

        @Override
        public eu.exeris.kernel.spi.bootstrap.BootstrapPhase phase() {
            return eu.exeris.kernel.spi.bootstrap.BootstrapPhase.FOUNDATION;
        }

        @Override
        public void initialize() {
            ConfigWatchProbe probe = WATCH_PROBE_REF.get();
            if (probe == null) {
                throw new IllegalStateException("ConfigWatchProbe not initialized");
            }

            KernelProviders.CURRENT_CONFIG.get()
                    .watch("runtime.properties", "dynamic.value", rawValue -> {
                        probe.value().set(String.valueOf(rawValue));
                        probe.latch().countDown();
                    });
        }

        @Override
        public void start() {
            // no-op
        }

        @Override
        public void stop() {
            // no-op
        }
    }

    private static final class ConfigWatchProbe {
        private final CountDownLatch latch = new CountDownLatch(1);
        private final AtomicReference<String> value = new AtomicReference<>();

        private CountDownLatch latch() {
            return latch;
        }

        private AtomicReference<String> value() {
            return value;
        }
    }

    // =========================================================================
    // DualServiceShimLoader — injects multiple ServiceLoader registrations
    // =========================================================================

    /**
     * Injects an arbitrary map of {@code serviceInterface → implementationClass}
     * bindings into the {@link java.util.ServiceLoader} discovery path by overriding
     * {@code META-INF/services} resource streams — no filesystem I/O required.
     *
     * <p>Supports multiple SPI registrations simultaneously (e.g. both
     * {@link ConfigProvider} and {@link eu.exeris.kernel.spi.bootstrap.SubsystemProvider}).
     */
    private static final class DualServiceShimLoader extends ClassLoader {

        /** {@code "META-INF/services/" + interfaceName → implementationClassName} */
        private final Map<String, String> resourceMap;

        DualServiceShimLoader(ClassLoader parent, Map<String, String> spiBindings) {
            super(parent);
            Map<String, String> mapped = new HashMap<>();
            spiBindings.forEach((iface, impl) ->
                    mapped.put("META-INF/services/" + iface, impl));
            this.resourceMap = Collections.unmodifiableMap(mapped);
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            String impl = resourceMap.get(name);
            if (impl != null) {
                return toStream(impl);
            }
            return super.getResourceAsStream(name);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            String impl = resourceMap.get(name);
            if (impl != null) {
                return Collections.enumeration(List.of(toInMemoryUrl(name, impl)));
            }
            return super.getResources(name);
        }

        // ── helpers ──────────────────────────────────────────────────────────

        private static InputStream toStream(String content) {
            return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        }

        @SuppressWarnings("PMD.AvoidCatchingGenericException")
        private static URL toInMemoryUrl(String resourcePath, String content) {
            try {
                byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                return new URL(null, "memory://" + resourcePath,
                        new URLStreamHandler() {
                            @Override
                            protected URLConnection openConnection(URL u) {
                                return new URLConnection(u) {
                                    @Override public void connect() { /* no-op */ }

                                    @Override
                                    public InputStream getInputStream() {
                                        return new ByteArrayInputStream(bytes);
                                    }
                                };
                            }
                        });
            } catch (Exception ex) {
                throw new IllegalStateException(
                        "DualServiceShimLoader: failed to build in-memory URL for "
                        + resourcePath, ex);
            }
        }
    }
}
