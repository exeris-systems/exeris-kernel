/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.events.outbox;

import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventPayload;

import java.util.List;

/**
 * Core: Port interface for the Outbox Orchestrator → physical broker bridge.
 *
 * <h2>Role</h2>
 * <p>The {@code OutboxBrokerPort} decouples the Outbox Orchestrator state machine from
 * the physical transport. The orchestrator polls {@link OutboxEventStore} for pending
 * events and delegates actual delivery to this port — which may be a JDBC-backed
 * community stub, a native Kafka client, or a Redpanda zero-copy publisher depending
 * on the runtime classpath.
 *
 * <h2>The Wall</h2>
 * <p>This interface lives in {@code exeris-kernel-core} (not SPI) because it is an
 * <em>internal orchestration port</em>, not a public kernel API. Implementations
 * (e.g. Community JDBC stub, Enterprise Kafka native) wire themselves to the
 * orchestrator via the {@link OutboxOrchestrator.Builder}.
 *
 * @since 0.5.0
 */
public interface OutboxBrokerPort {

    /**
     * Publishes a batch of outbox events to the physical broker.
     *
     * <p>Each {@link EventPayload} in the batch is owned by the caller for the duration
     * of this call. The port MUST NOT retain any payload reference after this method returns.
     *
     * @param batch ordered list of (descriptor, payload) pairs — never null, never empty
     * @return number of events successfully published
     */
    int publish(List<OutboxEntry> batch);

    /**
     * Returns the human-readable broker identifier for JFR telemetry.
     *
     * @return non-null identifier, e.g. {@code "noop"}, {@code "kafka-native"}, {@code "jdbc-pg"}
     */
    String brokerId();

    /**
     * Immutable carrier for a single outbox event dispatched to the broker port.
     *
     * <p>Valhalla-ready: all fields are either primitives or reference types that
     * the C2 JIT can scalarise via Escape Analysis when the record does not escape
     * the dispatch scope.
     *
     * @param descriptor routing metadata
     * @param payload    RAII payload — the port must NOT call retain() or close()
     */
    record OutboxEntry(EventDescriptor descriptor, EventPayload payload) {}
}
