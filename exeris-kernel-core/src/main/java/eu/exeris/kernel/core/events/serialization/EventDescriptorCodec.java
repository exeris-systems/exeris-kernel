/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.events.serialization;

import eu.exeris.kernel.spi.events.EventDescriptor;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;

/**
 * Core: Zero-copy binary codec for {@link EventDescriptor}.
 *
 * <h2>Wire Format (64 bytes = 1 cache line)</h2>
 * <pre>
 * Offset  Size  Field
 * ──────────────────────────────────────
 *  0       8B   eventIdHigh
 *  8       8B   eventIdLow
 * 16       8B   streamIdHigh
 * 24       8B   streamIdLow
 * 32       4B   eventTypeOrdinal
 * 36       4B   flags
 * 40       8B   occurredAtEpochMs
 * 48      16B   _padding (cache-line fill)
 * ──────────────────────────────────────
 * TOTAL: 64 bytes
 * </pre>
 *
 * <h2>Zero-Copy Contract</h2>
 * <p>{@link #write} stamps field values directly into the caller-supplied
 * {@link MemorySegment} at the given byte offset — no intermediate {@code byte[]}
 * copy, no heap allocation.
 *
 * <p>{@link #read} reconstructs the {@link EventDescriptor} record by reading
 * primitive fields directly from the segment — no deserialization objects.
 *
 * <h2>VarHandle Safety</h2>
 * <p>All field access uses {@code MemoryLayout}-derived {@link VarHandle}s —
 * no {@code sun.misc.Unsafe} (banned). The VarHandles are static finals,
 * initialised once at class-load time (zero overhead on the hot path).
 *
 * @since 0.5.0
 */
public final class EventDescriptorCodec {

    /** Wire size in bytes: 64 bytes = 1 CPU cache line. */
    public static final int WIRE_SIZE = 64;

    // ── MemoryLayout — single source of truth for field offsets ──────────────

    private static final MemoryLayout LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_LONG.withName("eventIdHigh"),
            ValueLayout.JAVA_LONG.withName("eventIdLow"),
            ValueLayout.JAVA_LONG.withName("streamIdHigh"),
            ValueLayout.JAVA_LONG.withName("streamIdLow"),
            ValueLayout.JAVA_INT.withName("eventTypeOrdinal"),
            ValueLayout.JAVA_INT.withName("flags"),
            ValueLayout.JAVA_LONG.withName("occurredAtEpochMs"),
            MemoryLayout.paddingLayout(16)
    );

    // ── VarHandles — static finals, zero hot-path overhead ───────────────────

    private static final VarHandle VH_EVENT_ID_HIGH =
            LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("eventIdHigh"));
    private static final VarHandle VH_EVENT_ID_LOW =
            LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("eventIdLow"));
    private static final VarHandle VH_STREAM_ID_HIGH =
            LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("streamIdHigh"));
    private static final VarHandle VH_STREAM_ID_LOW =
            LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("streamIdLow"));
    private static final VarHandle VH_EVENT_TYPE_ORDINAL =
            LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("eventTypeOrdinal"));
    private static final VarHandle VH_FLAGS =
            LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("flags"));
    private static final VarHandle VH_OCCURRED_AT =
            LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("occurredAtEpochMs"));

    private EventDescriptorCodec() {}

    /**
     * Writes an {@link EventDescriptor} into a {@link MemorySegment} at the given byte offset.
     *
     * <p>The segment must have at least {@code offset + WIRE_SIZE} bytes available.
     * This method performs zero heap allocations.
     *
     * @param segment    the target segment (non-null, must be writable)
     * @param offset     byte offset within the segment
     * @param descriptor the descriptor to encode (non-null)
     */
    public static void write(MemorySegment segment, long offset, EventDescriptor descriptor) {
        MemorySegment slice = segment.asSlice(offset, WIRE_SIZE);
        VH_EVENT_ID_HIGH.set(slice, 0L, descriptor.eventIdHigh());
        VH_EVENT_ID_LOW.set(slice, 0L, descriptor.eventIdLow());
        VH_STREAM_ID_HIGH.set(slice, 0L, descriptor.streamIdHigh());
        VH_STREAM_ID_LOW.set(slice, 0L, descriptor.streamIdLow());
        VH_EVENT_TYPE_ORDINAL.set(slice, 0L, descriptor.eventTypeOrdinal());
        VH_FLAGS.set(slice, 0L, descriptor.flags());
        VH_OCCURRED_AT.set(slice, 0L, descriptor.occurredAtEpochMs());
    }

    /**
     * Reads an {@link EventDescriptor} from a {@link MemorySegment} at the given byte offset.
     *
     * <p>The segment must have at least {@code offset + WIRE_SIZE} bytes available.
     * This method performs zero heap allocations beyond the {@code EventDescriptor} record itself
     * (a Valhalla-ready primitive record that the C2 JIT scalarises on the hot path via Escape Analysis).
     *
     * @param segment the source segment (non-null)
     * @param offset  byte offset within the segment
     * @return the reconstructed {@link EventDescriptor}
     */
    public static EventDescriptor read(MemorySegment segment, long offset) {
        MemorySegment slice = segment.asSlice(offset, WIRE_SIZE);
        return new EventDescriptor(
                (long) VH_EVENT_ID_HIGH.get(slice, 0L),
                (long) VH_EVENT_ID_LOW.get(slice, 0L),
                (long) VH_STREAM_ID_HIGH.get(slice, 0L),
                (long) VH_STREAM_ID_LOW.get(slice, 0L),
                (int)  VH_EVENT_TYPE_ORDINAL.get(slice, 0L),
                (int)  VH_FLAGS.get(slice, 0L),
                (long) VH_OCCURRED_AT.get(slice, 0L)
        );
    }

    /**
     * Returns the canonical {@link MemoryLayout} for off-heap descriptor structs.
     *
     * <p>Enterprise consumers use this layout to build their slab pools —
     * the layout is the single source of truth for field offsets.
     *
     * @return the 64-byte descriptor struct layout
     */
    public static MemoryLayout layout() {
        return LAYOUT;
    }
}
