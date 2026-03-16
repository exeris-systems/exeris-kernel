/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link DynamicConfigFileWatcher}.
 *
 * <p>Tests write real files to a {@link TempDir} and verify that the WatchService
 * delivers notifications. The VT watcher loop is blocked by {@code WatchService.take()}
 * — a JDK VT-safe park point — so tests use {@link CountDownLatch} with a 5-second
 * timeout to avoid indefinite hangs in CI.
 *
 * <h2>Test isolation</h2>
 * <p>Each test creates its own {@link KernelConfigRegistry} and
 * {@link DynamicConfigFileWatcher} — no shared state between tests.
 *
 * @since 0.5.0
 */
@DisplayName("DynamicConfigFileWatcher — integration tests")
class DynamicConfigFileWatcherTest {

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Nested
    @DisplayName("Lifecycle contract")
    class LifecycleContract {

        @Test
        @DisplayName("start() starts the watcher; isRunning() returns true")
        void startSetsRunningFlag(@TempDir Path dir) throws IOException {
            var registry = new KernelConfigRegistry();
            try (var watcher = new DynamicConfigFileWatcher(dir, registry,
                    DynamicConfigFileWatcher.PropertiesExtractor.INSTANCE)) {
                watcher.start();
                assertThat(watcher.isRunning()).isTrue();
            }
        }

        @Test
        @DisplayName("close() stops the watcher; isRunning() returns false")
        void closeClearsRunningFlag(@TempDir Path dir) throws IOException {
            var registry = new KernelConfigRegistry();
            try (var watcher = new DynamicConfigFileWatcher(dir, registry,
                    DynamicConfigFileWatcher.PropertiesExtractor.INSTANCE)) {
                watcher.start();
                watcher.close();

                assertThat(watcher.isRunning()).isFalse();
            }
        }

        @Test
        @DisplayName("start() is idempotent — calling twice does not throw or create extra threads")
        void startIsIdempotent(@TempDir Path dir) throws IOException {
            var registry = new KernelConfigRegistry();
            try (var watcher = new DynamicConfigFileWatcher(dir, registry,
                    DynamicConfigFileWatcher.PropertiesExtractor.INSTANCE)) {
                watcher.start();
                watcher.start(); // second call must be no-op
                assertThat(watcher.isRunning()).isTrue();
            }
        }

        @Test
        @DisplayName("start() rolls back running state when watch registration fails")
        void startRollsBackRunningStateOnRegistrationFailure(@TempDir Path dir) throws IOException {
            Path watchFile = Files.writeString(dir.resolve("not-a-directory.properties"), "x=y\n");
            var registry = new KernelConfigRegistry();
            try (var watcher = new DynamicConfigFileWatcher(watchFile, registry,
                    DynamicConfigFileWatcher.PropertiesExtractor.INSTANCE)) {
                assertThatThrownBy(watcher::start)
                        .isInstanceOf(IOException.class);
                assertThat(watcher.isRunning()).isFalse();
            }
        }

        @Test
        @DisplayName("close() is idempotent — calling twice does not throw")
        void closeIsIdempotent(@TempDir Path dir) throws IOException {
            var registry = new KernelConfigRegistry();
            var watcher  = new DynamicConfigFileWatcher(dir, registry,
                    DynamicConfigFileWatcher.PropertiesExtractor.INSTANCE);

            watcher.start();
            watcher.close();
            watcher.close(); // must not throw
            assertThat(watcher.isRunning()).isFalse();
        }
    }

    // =========================================================================
    // Hot-reload delivery
    // =========================================================================

    @Nested
    @DisplayName("File change → callback delivery")
    class HotReloadDelivery {

        @Test
        @Timeout(10)
        @DisplayName("Modifying a watched .properties file delivers the new value to the callback")
        void fileModificationDeliveredToCallback(@TempDir Path dir) throws Exception {
            Path configFile = dir.resolve("app.properties");
            Files.writeString(configFile, "network.port=8080\n");

            var registry  = new KernelConfigRegistry();
            var received   = new AtomicReference<String>();
            var latch      = new CountDownLatch(1);

            registry.register("app.properties", "network.port", v -> {
                received.set(v);
                latch.countDown();
            });

            var watcher = new DynamicConfigFileWatcher(dir, registry,
                    DynamicConfigFileWatcher.PropertiesExtractor.INSTANCE);
            watcher.start();

            try {
                // Overwrite the file with a new value
                Files.writeString(configFile, "network.port=9090\n",
                        StandardOpenOption.TRUNCATE_EXISTING);

                boolean delivered = latch.await(5, TimeUnit.SECONDS);
                assertThat(delivered)
                        .as("WatchService must deliver the file-change event within 5 seconds")
                        .isTrue();
                assertThat(received.get())
                        .as("Callback must receive the updated value '9090'")
                        .isEqualTo("9090");
            } finally {
                watcher.close();
            }
        }

        @Test
        @Timeout(10)
        @DisplayName("Only registrations matching the changed file+key receive the callback")
        void onlyMatchingRegistrationsFired(@TempDir Path dir) throws Exception {
            Path configFile = dir.resolve("feature.properties");
            Files.writeString(configFile, "flags.alpha=false\n");

            var registry   = new KernelConfigRegistry();
            var alphaValue  = new AtomicReference<String>();
            var betaValue   = new AtomicReference<String>("untouched");
            var latch       = new CountDownLatch(1);

            registry.register("feature.properties", "flags.alpha", v -> {
                alphaValue.set(v);
                latch.countDown();
            });
            // This registration should NOT fire — different key
            registry.register("feature.properties", "flags.beta", betaValue::set);

            var watcher = new DynamicConfigFileWatcher(dir, registry,
                    DynamicConfigFileWatcher.PropertiesExtractor.INSTANCE);
            watcher.start();

            try {
                Files.writeString(configFile, "flags.alpha=true\n",
                        StandardOpenOption.TRUNCATE_EXISTING);

                boolean delivered = latch.await(5, TimeUnit.SECONDS);
                assertThat(delivered).isTrue();
                assertThat(alphaValue.get()).isEqualTo("true");
                assertThat(betaValue.get())
                        .as("beta callback must NOT be invoked — key absent in updated file")
                        .isEqualTo("untouched");
            } finally {
                watcher.close();
            }
        }

        @Test
        @Timeout(10)
        @DisplayName("Creating a new file in the watched dir triggers the callback")
        void newFileCreationTriggerCallback(@TempDir Path dir) throws Exception {
            var registry = new KernelConfigRegistry();
            var received  = new AtomicReference<String>();
            var latch     = new CountDownLatch(1);

            registry.register("dynamic.properties", "feature.enabled", v -> {
                received.set(v);
                latch.countDown();
            });

            var watcher = new DynamicConfigFileWatcher(dir, registry,
                    DynamicConfigFileWatcher.PropertiesExtractor.INSTANCE);
            watcher.start();

            try {
                Path newFile = dir.resolve("dynamic.properties");
                Files.writeString(newFile, "feature.enabled=true\n");

                boolean delivered = latch.await(5, TimeUnit.SECONDS);
                assertThat(delivered)
                        .as("Creating a new config file must trigger the WatchService CREATE event")
                        .isTrue();
                assertThat(received.get()).isEqualTo("true");
            } finally {
                watcher.close();
            }
        }
    }

    // =========================================================================
    // PropertiesExtractor
    // =========================================================================

    @Nested
    @DisplayName("PropertiesExtractor")
    class PropertiesExtractorContract {

        @Test
        @DisplayName("Extracts a known key from a .properties file")
        void extractsKnownKey(@TempDir Path dir) throws Exception {
            Path file = dir.resolve("test.properties");
            Files.writeString(file, "network.port=9090\nnetwork.timeout=30000\n");

            String value = DynamicConfigFileWatcher.PropertiesExtractor.INSTANCE
                    .extract(file, "network.port");

            assertThat(value).isEqualTo("9090");
        }

        @Test
        @DisplayName("Returns null for a key absent from the .properties file")
        void returnsNullForAbsentKey(@TempDir Path dir) throws Exception {
            Path file = dir.resolve("test.properties");
            Files.writeString(file, "network.port=9090\n");

            String value = DynamicConfigFileWatcher.PropertiesExtractor.INSTANCE
                    .extract(file, "nonexistent.key");

            assertThat(value).isNull();
        }
    }
}

