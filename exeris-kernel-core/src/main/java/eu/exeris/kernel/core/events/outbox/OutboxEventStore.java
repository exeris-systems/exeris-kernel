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
 * Core: Port interface for reading pending events from the durable outbox store.
 *
 * <h2>Role</h2>
 * <p>The Outbox Orchestrator polls this port during its {@code POLLING} state.
 * Implementations range from a JDBC query against a PostgreSQL outbox table
 * (Community) to a zero-copy read from an off-heap ring buffer or PG Native wire
 * (Enterprise). The orchestrator is agnostic to the underlying store.
 *
 * <h2>At-Least-Once Guarantee</h2>
 * <p>{@link #markDelivered} is called <em>after</em> the broker port confirms
 * successful publication. If the JVM crashes between {@link #pollPending} and
 * {@link #markDelivered}, the events remain in the outbox and will be re-polled
 * on the next startup cycle — guaranteeing at-least-once delivery.
 *
 * @since 0.5.0
 */
public interface OutboxEventStore {

    /**
     * Polls up to {@code maxItems} undelivered events from the outbox.
     *
     * <p>Each returned entry holds a descriptor and a payload. The caller owns
     * the payloads for the duration of the dispatch cycle and MUST close each
     * payload after processing (or after passing it to {@link OutboxBrokerPort}).
     *
     * @param maxItems maximum number of events to return (must be &gt; 0)
     * @return ordered list of pending entries; empty list if the outbox is empty
     */
    List<OutboxBrokerPort.OutboxEntry> pollPending(int maxItems);

    /**
     * Marks the given descriptors as successfully delivered.
     *
     * <p>Implementations update the outbox table (JDBC {@code UPDATE} or a
     * logical delete) so that these events are not re-polled.
     *
     * @param delivered descriptors of events that have been confirmed delivered
     */
    void markDelivered(List<EventDescriptor> delivered);

    /**
     * Marks the given descriptor as failed after all retries are exhausted.
     *
     * <p>Implementations move the event to the Dead Letter Queue (DLQ) table.
     *
     * @param descriptor the failed event descriptor
     * @param payload    original payload (for DLQ storage — caller retains ownership)
     * @param reason     human-readable failure reason for the DLQ record
     */
    void moveToDlq(EventDescriptor descriptor, EventPayload payload, String reason);
}
