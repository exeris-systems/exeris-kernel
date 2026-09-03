/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.events;

/**
 * SPI: result of a durable {@link EventStreamAppender#append} — the committed per-stream sequence
 * (ADR-049).
 *
 * <p>{@code committedSequence} is the strictly-monotonic, 1-based sequence number the binding
 * assigned to the appended event within its {@link StreamId}; it is the stream's new head after the
 * append. An optimistic-concurrency caller passes the previous {@code committedSequence} back as the
 * next {@code expectedVersion}; an append-only caller (using {@link EventStreamAppender#ANY_VERSION})
 * ignores it or uses it purely for observability.
 *
 * <h2>Valhalla Readiness (JEP 401)</h2>
 * <p>A single primitive {@code long} field, no identity-sensitive collaborators — adding the
 * {@code value} modifier later requires zero field changes.
 *
 * @param committedSequence the 1-based per-stream sequence assigned to the appended event
 *        (the stream's new head); always {@code >= 1}
 * @since 0.10.0
 * @see EventStreamAppender
 */
public record AppendResult(long committedSequence) {

    /** Lowest valid committed sequence — the 1-based head of a stream's first event. */
    public static final long FIRST_SEQUENCE = 1L;

    /**
     * Compact constructor — validates the sequence is a valid 1-based head.
     */
    public AppendResult {
        if (committedSequence < FIRST_SEQUENCE) {
            throw new IllegalArgumentException(
                    "committedSequence must be >= 1 (1-based per-stream head), was " + committedSequence);
        }
    }
}
