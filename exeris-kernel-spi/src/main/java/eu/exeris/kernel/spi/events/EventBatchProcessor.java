/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.events;

import java.util.List;

/**
 * SPI: Batch-oriented event processor for the {@link EventLoop}.
 *
 * <h2>Why Batching?</h2>
 * <p>The {@link EventLoop} drains the {@link EventQueue} per tick in batches.
 * Batch processing amortises fixed overheads: one DB statement for N outbox rows,
 * one native transport flush for N payloads, one graph mutation for N edge changes.
 *
 * <h2>Payload Lifecycle Contract</h2>
 * <p>Each {@link EventPayload} in {@code payloads} is owned by the batch processor
 * for the duration of {@link #processBatch}. The processor MUST close every payload
 * it receives — either directly with try-with-resources, or by retaining it before
 * passing it to another thread (and closing it there).
 *
 * <p>Indices are aligned: {@code descriptors.get(i)} corresponds to {@code payloads.get(i)}.
 *
 * <p>The lists are <b>read-only views</b> — do not mutate them.
 *
 * @since 0.5
 * @see EventLoop#registerProcessor(String, EventBatchProcessor)
 */
@FunctionalInterface
public interface EventBatchProcessor {

    /**
     * Processes a batch of events.
     *
     * <p>All descriptors share the same {@link EventDescriptor#eventTypeOrdinal()}.
     * The batch is FIFO-ordered for types with the {@link EventDescriptor#FLAG_ORDERED} flag.
     *
     * @param descriptors routing metadata list — read-only, same size as {@code payloads}
     * @param payloads    RAII payloads — same index as descriptors; processor MUST close each
     */
    void processBatch(List<EventDescriptor> descriptors, List<EventPayload> payloads);
}
