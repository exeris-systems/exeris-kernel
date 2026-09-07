/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.persistence;

import eu.exeris.kernel.spi.exceptions.persistence.PersistenceProviderException;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * SPI: Transactional Outbox contract — guaranteed at-least-once domain event delivery.
 *
 * <h2>The Outbox Pattern</h2>
 * <p>Domain events are written to the outbox table in the <b>same transaction</b> as
 * the aggregate mutation. This guarantees that if the transaction commits, the event
 * will eventually be delivered — and if the transaction rolls back, the event is
 * discarded atomically. No dual-write, no message loss.
 *
 * <h2>Delivery Guarantee</h2>
 * <ul>
 *   <li><b>At-least-once:</b> The {@code exeris-kernel-core} Outbox Poller reads
 *       pending events and publishes them to the event bus. Events remain in the
 *       outbox until {@link #markPublished(UUID)} is called.</li>
 *   <li><b>Ordering:</b> Events are polled in insertion order per aggregate ID.
 *       Cross-aggregate ordering is NOT guaranteed.</li>
 * </ul>
 *
 * <h2>Isolation Transparency</h2>
 * <p>Like all persistence contracts, isolation is handled transparently by the
 * Core layer. The {@code EventStore} implementation does not inspect the
 * {@link eu.exeris.kernel.spi.security.StorageContext} — it is injected by the
 * {@link ConnectionInterceptor} before any SQL is issued.
 *
 * @implSpec An Enterprise-tier implementation writes the {@link OutboxEvent} {@code payload}
 *           bytes directly from off-heap through
 *           {@link eu.exeris.kernel.spi.memory.LoanedBuffer}, rather than staging them on the
 *           heap first.
 * @since 0.5
 * @see BaseRepository
 * @see ConnectionInterceptor
 */
public interface EventStore {

    /**
     * Appends a domain event to the outbox within the current transaction.
     *
     * <p>The event is not visible to the Outbox Poller until that transaction commits, and is
     * discarded with it on rollback — which is what makes the delivery guarantee atomic with the
     * aggregate mutation.
     *
     * @param event the domain event to persist; never {@code null}
     * @throws PersistenceProviderException
     *         {@value eu.exeris.kernel.spi.exceptions.KernelErrorCodes#EX_PERS_5003} on write
     *         failure
     * @throws IllegalStateException        if no transaction is active
     * @apiNote Call this inside the same transaction as the aggregate write; an append on its own
     *          connection buys nothing the pattern is for.
     */
    void append(OutboxEvent event);

    /**
     * Returns a batch of events the poller has not yet marked published.
     *
     * @param maxBatchSize maximum number of events to return; MUST be &gt; 0
     * @return ordered list of pending events (oldest first); never {@code null}
     * @throws PersistenceProviderException
     *         {@value eu.exeris.kernel.spi.exceptions.KernelErrorCodes#EX_PERS_5003} on query
     *         failure
     * @implSpec An implementation SHOULD use {@code SELECT ... FOR UPDATE SKIP LOCKED}, so that
     *           two pollers running at once take disjoint batches instead of blocking on each
     *           other.
     */
    List<OutboxEvent> pollPending(int maxBatchSize);

    /**
     * Marks an event as successfully published, taking it out of the poller's pending set.
     *
     * @param eventId the event identifier to mark; never {@code null}
     * @throws PersistenceProviderException
     *         {@value eu.exeris.kernel.spi.exceptions.KernelErrorCodes#EX_PERS_5003} on update
     *         failure
     * @implSpec An implementation MAY delete the row or set a {@code published_at} timestamp, and
     *           MUST be idempotent — delivery is at-least-once, so the same id does arrive twice.
     */
    void markPublished(UUID eventId);

    /**
     * Outbox event — a Valhalla-ready record carrier.
     *
     * <p>All components are immutable, {@code payload} included: the array is copied on the way
     * in and on the way out, so no holder of a reference can mutate a stored event.
     *
     * <p><b>Allocation:</b> allocates (one defensive copy of {@code payload} at construction, and
     * one more on every {@link OutboxEvent#payload()} call).
     * <p><b>Thread confinement:</b> any thread — the record is deeply immutable once constructed.
     * <p><b>Ownership:</b> nothing to release; the array returned by {@link OutboxEvent#payload()}
     * is the caller's own copy and mutating it does not touch the event.
     *
     * @param eventId       unique event identifier (UUIDv7 for B-Tree insert locality)
     * @param aggregateId   identifier of the aggregate that produced the event
     * @param aggregateType simple class name of the aggregate root (e.g., {@code "Order"})
     * @param eventType     fully-qualified event type name (e.g., {@code "OrderPlaced"})
     * @param payload       serialised event payload bytes; never {@code null} or empty
     * @param occurredAt    epoch-millisecond timestamp of when the event was raised
     * @since 0.5
     */
    record OutboxEvent(
            UUID eventId,
            String aggregateId,
            String aggregateType,
            String eventType,
            byte[] payload,
            long occurredAt
    ) {
        /**
         * Rejects an incomplete event at construction and takes the record's own copy of
         * {@code payload}.
         *
         * @param eventId       unique event identifier; must not be {@code null}
         * @param aggregateId   identifier of the aggregate that produced the event; must not be
         *                      {@code null} or blank
         * @param aggregateType simple class name of the aggregate root; must not be {@code null}
         *                      or blank
         * @param eventType     fully-qualified event type name; must not be {@code null} or blank
         * @param payload       serialised event payload bytes; must not be {@code null} or empty;
         *                      defensively copied in
         * @param occurredAt    epoch-millisecond timestamp of when the event was raised
         * @throws IllegalArgumentException if {@code eventId} is {@code null}, if
         *                                  {@code aggregateId}, {@code aggregateType} or
         *                                  {@code eventType} is {@code null} or blank, or if
         *                                  {@code payload} is {@code null} or empty
         */
        public OutboxEvent {
            if (eventId == null) {
                throw new IllegalArgumentException("eventId must not be null");
            }
            if (aggregateId == null || aggregateId.isBlank()) {
                throw new IllegalArgumentException("aggregateId must not be blank");
            }
            if (aggregateType == null || aggregateType.isBlank()) {
                throw new IllegalArgumentException("aggregateType must not be blank");
            }
            if (eventType == null || eventType.isBlank()) {
                throw new IllegalArgumentException("eventType must not be blank");
            }
            if (payload == null || payload.length == 0) {
                throw new IllegalArgumentException("payload must not be null or empty");
            }
            // Defensive copy — byte[] is mutable; record must own its data (Valhalla-ready deep immutability)
            payload = Arrays.copyOf(payload, payload.length);
        }

        /**
         * Returns a defensive copy of the payload bytes.
         *
         * <p>Overrides the generated accessor to prevent callers from mutating the
         * internal array after construction — completing the deep immutability contract.
         * This is consistent with the defensive copy performed in the compact constructor.
         *
         * @return copy of the serialised event payload; never {@code null}
         */
        @Override
        public byte[] payload() {
            return Arrays.copyOf(payload, payload.length);
        }

        /**
         * Deep equality check — compares {@code payload} by content, not reference.
         */
        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof OutboxEvent(
                    UUID otherEventId,
                    String otherAggregateId,
                    String otherAggregateType,
                    String otherEventType,
                    byte[] otherPayload,
                    long otherOccurredAt
            ))) {
                return false;
            }
            return occurredAt == otherOccurredAt
                    && eventId.equals(otherEventId)
                    && aggregateId.equals(otherAggregateId)
                    && aggregateType.equals(otherAggregateType)
                    && eventType.equals(otherEventType)
                    && Arrays.equals(payload, otherPayload);
        }

        /**
         * Deep hash code — incorporates {@code payload} content.
         */
        @Override
        public int hashCode() {
            int result = eventId.hashCode();
            result = 31 * result + aggregateId.hashCode();
            result = 31 * result + aggregateType.hashCode();
            result = 31 * result + eventType.hashCode();
            result = 31 * result + Arrays.hashCode(payload);
            result = 31 * result + Long.hashCode(occurredAt);
            return result;
        }

        /**
         * Returns a string representation with {@code payload} rendered as byte count
         * to avoid log bloat for large payloads.
         */
        @Override
        public String toString() {
            return "OutboxEvent["
                    + "eventId=" + eventId
                    + ", aggregateId=" + aggregateId
                    + ", aggregateType=" + aggregateType
                    + ", eventType=" + eventType
                    + ", payload=" + payload.length + "B"
                    + ", occurredAt=" + occurredAt
                    + ']';
        }
    }
}
