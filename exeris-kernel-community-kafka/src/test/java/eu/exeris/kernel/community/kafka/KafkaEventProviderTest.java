/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.kafka;

import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.events.EventProviderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link KafkaEventProvider}: its discovery priority and the optional client
 * property, client id and loop-thread keys it reads from the bound {@link ConfigProvider}.
 *
 * <p>{@link KafkaEventProvider#PRIORITY} is a semantically significant constant: Kafka must
 * outrank the in-memory Community {@code EventProvider} ({@code 0}) on {@code ServiceLoader}
 * resolution, yet stay below the Enterprise tier slot ({@code 100}). That makes {@code 50}
 * intra-Community precedence, not the open-core tier convention. This test catches a silent
 * drift of that value (the config provider side has equivalent {@code isEqualTo(0)} pins).
 */
@DisplayName("KafkaEventProvider — priority and client configuration keys")
class KafkaEventProviderTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("priority() is 50 — intra-Community precedence, below the Enterprise slot (100)")
    void priorityIsIntraCommunitySlot() {
        assertThat(new KafkaEventProvider().priority())
                .as("priority() must return the PRIORITY constant")
                .isEqualTo(KafkaEventProvider.PRIORITY);
        assertThat(KafkaEventProvider.PRIORITY)
                .as("Kafka outranks in-memory Community (0) but stays below Enterprise (100)")
                .isEqualTo(50);
    }

    @Test
    @DisplayName("properties files, client-id and consumer-loop-thread are read into the config")
    void newKeysAreReadIntoTheConfig() throws IOException {
        Path producer = Files.writeString(tmp.resolve("producer.properties"),
                "acks=0\ncompression.type=lz4\n");
        Path consumer = Files.writeString(tmp.resolve("consumer.properties"),
                "max.poll.records=7\n");
        Map<String, String> values = baseValues();
        values.put("events.kafka.producer-properties-file", producer.toString());
        values.put("events.kafka.consumer-properties-file", consumer.toString());
        values.put("events.kafka.client-id", "my-client");
        values.put("events.kafka.consumer-loop-thread", "PLATFORM");

        KafkaEventConfig cfg = readConfig(values);

        assertThat(cfg.producerOverrides())
                .containsEntry("acks", "0").containsEntry("compression.type", "lz4").hasSize(2);
        assertThat(cfg.consumerOverrides()).containsEntry("max.poll.records", "7").hasSize(1);
        assertThat(cfg.clientId()).isEqualTo("my-client");
        assertThat(cfg.loopThread()).isEqualTo(KafkaEventConfig.LoopThread.PLATFORM);
    }

    @Test
    @DisplayName("absent keys leave empty overrides, empty client id and a virtual loop thread")
    void absentKeysUseDefaults() {
        KafkaEventConfig cfg = readConfig(baseValues());

        assertThat(cfg.producerOverrides()).isEmpty();
        assertThat(cfg.consumerOverrides()).isEmpty();
        assertThat(cfg.clientId()).isEmpty();
        assertThat(cfg.loopThread()).isEqualTo(KafkaEventConfig.LoopThread.VIRTUAL);
    }

    @Test
    @DisplayName("close-timeout-ms is read into closeTimeout, defaulting to 30 s")
    void closeTimeoutKeyIsRead() {
        assertThat(readConfig(baseValues()).closeTimeout()).isEqualTo(Duration.ofSeconds(30));

        Map<String, String> values = baseValues();
        values.put("events.kafka.close-timeout-ms", "1500");

        assertThat(readConfig(values).closeTimeout()).isEqualTo(Duration.ofMillis(1500));
    }

    @Test
    @DisplayName("a non-positive close-timeout-ms is a creation failure")
    void nonPositiveCloseTimeoutFails() {
        Map<String, String> values = baseValues();
        values.put("events.kafka.close-timeout-ms", "0");

        assertThatThrownBy(() -> readConfig(values))
                .isInstanceOf(EventProviderException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a missing properties file is a creation failure naming the path, with the IOException as cause")
    void missingFileFails() {
        Path missing = tmp.resolve("absent.properties");
        Map<String, String> values = baseValues();
        values.put("events.kafka.producer-properties-file", missing.toString());

        assertThatThrownBy(() -> readConfig(values))
                .isInstanceOf(EventProviderException.class)
                .hasCauseInstanceOf(IOException.class)
                .satisfies(e -> assertThat(Arrays.toString(((EventProviderException) e).rawArgs()))
                        .contains(missing.toString()));
    }

    @Test
    @DisplayName("a properties-file value that is not a path is a creation failure, not a raw InvalidPathException")
    void malformedPathFails() {
        Map<String, String> values = baseValues();
        values.put("events.kafka.consumer-properties-file", "consumer\u0000.properties");

        assertThatThrownBy(() -> readConfig(values))
                .isInstanceOf(EventProviderException.class)
                .hasCauseInstanceOf(java.nio.file.InvalidPathException.class);
    }

    @Test
    @DisplayName("an invalid consumer-loop-thread value is a creation failure naming the key")
    void invalidLoopThreadFails() {
        Map<String, String> values = baseValues();
        values.put("events.kafka.consumer-loop-thread", "fiber");

        assertThatThrownBy(() -> readConfig(values))
                .isInstanceOf(EventProviderException.class)
                .satisfies(e -> assertThat(Arrays.toString(((EventProviderException) e).rawArgs()))
                        .contains("events.kafka.consumer-loop-thread"));
    }

    @Test
    @DisplayName("loadOverrides reads a properties file into a map")
    void loadOverridesReadsFile() throws IOException {
        Path file = Files.writeString(tmp.resolve("x.properties"), "a=1\nb = two\n");

        Map<String, String> loaded = KafkaEventProvider.loadOverrides(file);

        assertThat(loaded).containsOnly(Map.entry("a", "1"), Map.entry("b", "two"));
    }

    private static Map<String, String> baseValues() {
        Map<String, String> values = new HashMap<>();
        values.put("events.kafka.bootstrap-servers", "localhost:1");
        values.put("events.kafka.group-id", "provider-test");
        return values;
    }

    /** Runs readKafkaConfig under a bound config and returns the result (or rethrows). */
    private static KafkaEventConfig readConfig(Map<String, String> values) {
        AtomicReference<KafkaEventConfig> captured = new AtomicReference<>();
        ScopedValue.where(KernelProviders.CURRENT_CONFIG, new MapConfig(values))
                .run(() -> captured.set(KafkaEventProvider.readKafkaConfig()));
        return captured.get();
    }

    private record MapConfig(Map<String, String> values) implements ConfigProvider {

        @Override
        public Supplier<ConfigProvider.KernelSettings> kernelSettings() {
            return ConfigProvider.KernelSettings::defaults;
        }

        @Override
        public Optional<String> getString(String key) {
            return Optional.ofNullable(values.get(key));
        }

        @Override
        public Optional<Integer> getInt(String key) {
            return Optional.empty();
        }

        @Override
        public Optional<Long> getLong(String key) {
            return Optional.ofNullable(values.get(key)).map(Long::valueOf);
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
            // no hot-reload in this test double
        }
    }
}
