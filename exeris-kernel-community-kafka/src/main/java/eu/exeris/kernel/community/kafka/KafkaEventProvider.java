/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.kafka;

import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.events.EventEngine;
import eu.exeris.kernel.spi.events.EventEngineConfig;
import eu.exeris.kernel.spi.events.EventProvider;
import eu.exeris.kernel.spi.exceptions.events.EventProviderException;

import java.time.Duration;
import java.util.Objects;

/**
 * {@link EventProvider} implementation for the Community Kafka binding (since 0.7.0).
 *
 * <h2>Discovery</h2>
 * <p>Registered via {@code META-INF/services/eu.exeris.kernel.spi.events.EventProvider}.
 * Priority is intentionally higher than the in-memory Community provider (priority {@code 50}
 * vs Community's {@code 0}) so that {@code exeris-kernel-community-kafka} wins
 * {@code ServiceLoader} resolution when both modules are on the classpath. It stays below the
 * Enterprise tier slot ({@code 100}): this is intra-Community precedence, not the open-core tier
 * convention, so an Enterprise overlay still outranks it.
 *
 * <h2>Configuration Sources</h2>
 * <p>Kafka-specific knobs are read from the active {@link KernelProviders#CURRENT_CONFIG} when
 * the slot is bound. Single-binding tests (no {@code ConfigProvider} on the carrier) may use
 * {@link #create(EventEngineConfig, KafkaEventConfig)} which short-circuits the lookup.
 *
 * @since 0.7.0
 */
public final class KafkaEventProvider implements EventProvider {

    /**
     * Default priority — above in-memory Community ({@code 0}) so Kafka wins ServiceLoader, and
     * below the Enterprise tier slot ({@code 100}). Intra-Community precedence, not a tier value.
     */
    public static final int PRIORITY = 50;

    private static final String PROVIDER_ID   = "community-kafka";
    private static final String PROVIDER_NAME = "ExerisCommunityKafka/Events";

    /** Key prefix used to look up Kafka config under {@code KernelProviders.config()}. */
    private static final String CFG_BOOTSTRAP_SERVERS  = "events.kafka.bootstrap-servers";
    private static final String CFG_GROUP_ID           = "events.kafka.group-id";
    private static final String CFG_TOPIC_PREFIX       = "events.kafka.topic-prefix";
    private static final String CFG_REQUIRE_ALL_ACKS   = "events.kafka.require-all-acks";
    private static final String CFG_LINGER_MS          = "events.kafka.producer-linger-ms";
    private static final String CFG_POLL_TIMEOUT_MS    = "events.kafka.consumer-poll-timeout-ms";

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public int priority() {
        return PRIORITY;
    }

    @Override
    public EventEngine createEngine(EventEngineConfig config) {
        if (config == null) {
            throw EventProviderException.creationFailure(PROVIDER_NAME,
                    "config must not be null", null);
        }
        KafkaEventConfig kafkaConfig = readKafkaConfig();
        return create(config, kafkaConfig);
    }

    /**
     * Direct factory used by tests / TCK bindings that have already constructed an explicit
     * {@link KafkaEventConfig} (e.g. from a Testcontainers bootstrap address).
     */
    public static KafkaEventEngine create(EventEngineConfig spi, KafkaEventConfig kafka) {
        Objects.requireNonNull(spi,   "spi config must not be null");
        Objects.requireNonNull(kafka, "kafka config must not be null");
        return new KafkaEventEngine(spi, kafka);
    }

    private static KafkaEventConfig readKafkaConfig() {
        if (!KernelProviders.CURRENT_CONFIG.isBound()) {
            throw EventProviderException.creationFailure(PROVIDER_NAME,
                    "ConfigProvider not bound; bind KernelProviders.CURRENT_CONFIG before "
                    + "ServiceLoader-driven createEngine() or use the explicit create(spi,kafka) factory",
                    null);
        }
        ConfigProvider cfg = KernelProviders.config();
        String bootstrapServers = cfg.getString(CFG_BOOTSTRAP_SERVERS).orElseThrow(() ->
                EventProviderException.creationFailure(PROVIDER_NAME,
                        "missing required config '" + CFG_BOOTSTRAP_SERVERS + '\'', null));
        String groupId = cfg.getString(CFG_GROUP_ID).orElseThrow(() ->
                EventProviderException.creationFailure(PROVIDER_NAME,
                        "missing required config '" + CFG_GROUP_ID + '\'', null));
        String topicPrefix = cfg.getString(CFG_TOPIC_PREFIX).orElse(KafkaEventConfig.EMPTY_TOPIC_PREFIX);
        boolean requireAllAcks = cfg.getBoolean(CFG_REQUIRE_ALL_ACKS).orElse(true);
        long lingerMs = cfg.getLong(CFG_LINGER_MS).orElse(5L);
        long pollTimeoutMs = cfg.getLong(CFG_POLL_TIMEOUT_MS).orElse(250L);
        return new KafkaEventConfig(
                bootstrapServers,
                groupId,
                topicPrefix,
                requireAllAcks,
                lingerMs,
                Duration.ofMillis(pollTimeoutMs));
    }
}
