/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.events.outbox;

import eu.exeris.kernel.core.events.jfr.OutboxBatchFlushedEvent;
import eu.exeris.kernel.core.events.jfr.OutboxDlqEvent;
import eu.exeris.kernel.spi.events.EventDescriptor;
import eu.exeris.kernel.spi.events.EventPayload;
import jdk.jfr.FlightRecorder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

/**
 * Batch flush + retry + DLQ delivery for {@link OutboxOrchestrator}.
 *
 * <p>Extracted from {@link OutboxOrchestrator} in v0.8 Sprint 1 (QA-014) to close
 * the orchestrator's God-class suppression block. Owns:
 *
 * <ul>
 *   <li>{@link #flush(List)} — publishes a batch via the broker port, marks
 *       delivered descriptors in the store, and routes partial failures to
 *       per-event retries.</li>
 *   <li>Per-event retry loop with linear backoff ({@code pollIntervalNanos *
 *       attempt}).</li>
 *   <li>DLQ delivery + {@link OutboxDlqEvent} JFR emission on retry
 *       exhaustion or unhandled retry exception.</li>
 *   <li>{@link OutboxBatchFlushedEvent} JFR emission summarising every flush
 *       cycle (size, duration nanos, failed count).</li>
 *   <li>Payload close after flush — release {@link EventPayload} loans
 *       regardless of outcome.</li>
 * </ul>
 *
 * <p>State transitions during partial failure are routed through the supplied
 * {@link OutboxStateMachine}; this class does not own state itself.
 */
@SuppressWarnings({
    "PMD.AvoidCatchingGenericException", // broker.publish() and retry path catch RuntimeException by contract.
    "PMD.CloseResource"                  // payload close is intentional and routed through closeEntryPayloads.
})
final class OutboxBatchFlusher {

    private static final int SINGLE_EVENT_PUBLISHED = 1;

    private final OutboxEventStore eventStore;
    private final OutboxBrokerPort brokerPort;
    private final OutboxStateMachine stateMachine;
    private final int maxRetries;
    private final long pollIntervalNanos;

    /* default */ OutboxBatchFlusher(OutboxEventStore eventStore,
                                     OutboxBrokerPort brokerPort,
                                     OutboxStateMachine stateMachine,
                                     int maxRetries,
                                     long pollIntervalNanos) {
        this.eventStore = eventStore;
        this.brokerPort = brokerPort;
        this.stateMachine = stateMachine;
        this.maxRetries = maxRetries;
        this.pollIntervalNanos = pollIntervalNanos;
    }

    /* default */ void flush(List<OutboxBrokerPort.OutboxEntry> batch) {
        long startNanos = System.nanoTime();
        int  failed     = batch.size(); // assume all failed until broker confirms

        List<EventDescriptor> toDeliver = new ArrayList<>(batch.size());
        try {
            int rawPublished = brokerPort.publish(batch);
            int published = Math.clamp(rawPublished, 0, batch.size());
            failed = batch.size() - published;

            for (int i = 0; i < published; i++) {
                toDeliver.add(batch.get(i).descriptor());
            }
            if (!toDeliver.isEmpty()) {
                eventStore.markDelivered(toDeliver);
            }

            if (failed > 0) {
                handlePartialFailure(batch, published);
            }
        } catch (RuntimeException ex) {
            // broker.publish() threw — entire batch failed; failed is already batch.size()
            handlePartialFailure(batch, 0);
            throw ex;
        } finally {
            closeEntryPayloads(batch);
            emitBatchFlushed(batch.size() - failed, System.nanoTime() - startNanos, failed);
        }
    }

    private void handlePartialFailure(List<OutboxBrokerPort.OutboxEntry> batch, int successCount) {
        stateMachine.transitionTo(OutboxStateMachine.RETRYING, 0);
        for (int i = successCount; i < batch.size(); i++) {
            OutboxBrokerPort.OutboxEntry entry = batch.get(i);
            try {
                boolean recovered = retryEntry(entry);
                if (!recovered) {
                    emitDlqTransition(entry, "max retries exhausted");
                    eventStore.moveToDlq(entry.descriptor(), entry.payload(), "max retries exhausted");
                }
            } catch (RuntimeException ex) {
                String message = ex.getMessage();
                String reason = (message == null || message.isBlank()) ? "exception" : message;
                emitDlqTransition(entry, reason);
                eventStore.moveToDlq(entry.descriptor(), entry.payload(), reason);
            }
        }
    }

    private boolean retryEntry(OutboxBrokerPort.OutboxEntry entry) {
        List<OutboxBrokerPort.OutboxEntry> single = List.of(entry);
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                if (brokerPort.publish(single) == SINGLE_EVENT_PUBLISHED) {
                    eventStore.markDelivered(List.of(entry.descriptor()));
                    return true;
                }
            } catch (RuntimeException _) {
                // retry on next attempt
            }
            LockSupport.parkNanos(pollIntervalNanos * attempt);
        }
        return false;
    }

    private void emitDlqTransition(OutboxBrokerPort.OutboxEntry entry, String reason) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        OutboxDlqEvent dlqEvt = new OutboxDlqEvent();
        if (dlqEvt.isEnabled()) {
            dlqEvt.eventType    = String.valueOf(entry.descriptor().eventTypeOrdinal());
            dlqEvt.streamIdHigh = entry.descriptor().streamIdHigh();
            dlqEvt.streamIdLow  = entry.descriptor().streamIdLow();
            dlqEvt.reason       = reason;
            dlqEvt.retryCount   = maxRetries;
            dlqEvt.commit();
        }
    }

    private static void closeEntryPayloads(List<OutboxBrokerPort.OutboxEntry> batch) {
        for (OutboxBrokerPort.OutboxEntry entry : batch) {
            EventPayload payload = entry.payload();
            if (payload != null && payload.isAlive()) {
                payload.close();
            }
        }
    }

    private static void emitBatchFlushed(int size, long durationNanos, int failed) {
        if (!FlightRecorder.isInitialized()) {
            return;
        }
        OutboxBatchFlushedEvent evt = new OutboxBatchFlushedEvent();
        if (evt.isEnabled()) {
            evt.batchSize          = size;
            evt.flushDurationNanos = durationNanos;
            evt.failedCount        = failed;
            evt.commit();
        }
    }
}
