/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.kafka;

import eu.exeris.kernel.spi.events.EventTypeSpec;

import java.util.HashSet;
import java.util.Set;

/**
 * The topics that carry Exeris-framed records: the event-log topic and the effective topic of every
 * registered event type. The raw record channel may neither subscribe to nor send into them.
 *
 * <p>{@link #contains(String)} is on the raw send path, so it reads a snapshot that is rebuilt only
 * when the registry's version moves, and allocates nothing otherwise.
 */
final class KafkaFramedTopics {

    private final KafkaEventRegistry registry;
    private final KafkaEventConfig config;
    private volatile Snapshot snapshot = new Snapshot(-1, Set.of());

    private record Snapshot(int registryVersion, Set<String> topics) { }

    /* default */ KafkaFramedTopics(KafkaEventRegistry registry, KafkaEventConfig config) {
        this.registry = registry;
        this.config = config;
    }

    /* default */ boolean contains(String topic) {
        Snapshot current = snapshot;
        // Read before the snapshot is built: a registration that lands in between only makes the
        // snapshot newer than its version says, and the next call rebuilds it.
        int registryVersion = registry.registeredVersion();
        if (current.registryVersion() != registryVersion) {
            current = new Snapshot(registryVersion, build());
            snapshot = current;
        }
        return current.topics().contains(topic);
    }

    /**
     * Says why the topic is off limits to the raw channel, or returns {@code null} when it is free.
     * Not on a hot path: it walks the registry to name the event type.
     */
    /* default */ String describe(String topic) {
        if (topic.equals(config.eventLogTopic())) {
            return "the event-log topic";
        }
        for (String type : registry.registeredTypes()) {
            EventTypeSpec spec = registry.resolve(type);
            if (spec != null && topic.equals(KafkaEventEngine.effectiveTopic(spec, config))) {
                return "the topic of registered event type '" + type + "'";
            }
        }
        return null;
    }

    private Set<String> build() {
        Set<String> topics = new HashSet<>();
        topics.add(config.eventLogTopic());
        for (String type : registry.registeredTypes()) {
            EventTypeSpec spec = registry.resolve(type);
            if (spec != null) {
                topics.add(KafkaEventEngine.effectiveTopic(spec, config));
            }
        }
        return Set.copyOf(topics);
    }
}
