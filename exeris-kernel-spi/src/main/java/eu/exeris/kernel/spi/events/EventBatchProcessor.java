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
 * for the duration of {@link #processBatch}. Indices are aligned:
 * {@code descriptors.get(i)} corresponds to {@code payloads.get(i)}.
 *
 * <p><b>Ownership:</b> the processor owns one reference to every payload in the batch and owes
 * one {@link EventPayload#close()} for each it processes; the loop's own tracking wrapper closes
 * any it does not reach on an unwind
 *
 * @implSpec An implementation closes every payload it receives — directly with
 *           try-with-resources, or by {@link EventPayload#retain()}ing it before handing it to
 *           another thread that closes it there. It treats both lists as read-only views and does
 *           not mutate them.
 * @since 0.5
 * @see EventLoop#registerProcessor(String, EventBatchProcessor)
 */
@FunctionalInterface
public interface EventBatchProcessor {

    /**
     * Consumes one drained batch, amortising whatever fixed cost the processor carries — a single
     * statement for N outbox rows, a single flush for N payloads — across the whole batch.
     *
     * <p>All descriptors share the same {@link EventDescriptor#eventTypeOrdinal()}.
     * The batch is FIFO-ordered for types with the {@link EventDescriptor#FLAG_ORDERED} flag.
     *
     * @param descriptors routing metadata list — read-only, same size as {@code payloads}
     * @param payloads    RAII payloads — same index as descriptors; the processor closes each
     * @apiNote Registered through
     *          {@link EventLoop#registerProcessor(String, EventBatchProcessor)}; the loop calls
     *          this once per tick with whatever it drained, so an implementation sees a batch of
     *          one as readily as a full one and should not assume a minimum size.
     */
    void processBatch(List<EventDescriptor> descriptors, List<EventPayload> payloads);
}
