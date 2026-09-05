/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.events.serialization;

import eu.exeris.kernel.spi.events.EventDescriptor;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Core: Zero-copy binary codec for {@link EventDescriptor}.
 *
 * <h2>Wire Format (64 bytes = 1 cache line)</h2>
 * <p>All multi-byte fields are fixed <b>LITTLE_ENDIAN</b> — format is portable across
 * architectures regardless of host native byte order.
 * <pre>
 * Offset  Size  Field
 * ──────────────────────────────────────
 *  0       8B   eventIdHigh      (LE)
 *  8       8B   eventIdLow       (LE)
 * 16       8B   streamIdHigh     (LE)
 * 24       8B   streamIdLow      (LE)
 * 32       4B   eventTypeOrdinal (LE)
 * 36       4B   flags            (LE)
 * 40       8B   occurredAtEpochMs (LE)
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
 * @since 0.5
 */
public final class EventDescriptorCodec {

    /** Wire size in bytes: 64 bytes = 1 CPU cache line. */
    public static final int WIRE_SIZE = 64;

    // ── MemoryLayout — single source of truth for field offsets ──────────────

    private static final MemoryLayout LAYOUT = MemoryLayout.structLayout(
            ValueLayout.JAVA_LONG.withOrder(ByteOrder.LITTLE_ENDIAN).withName("eventIdHigh"),
            ValueLayout.JAVA_LONG.withOrder(ByteOrder.LITTLE_ENDIAN).withName("eventIdLow"),
            ValueLayout.JAVA_LONG.withOrder(ByteOrder.LITTLE_ENDIAN).withName("streamIdHigh"),
            ValueLayout.JAVA_LONG.withOrder(ByteOrder.LITTLE_ENDIAN).withName("streamIdLow"),
            ValueLayout.JAVA_INT.withOrder(ByteOrder.LITTLE_ENDIAN).withName("eventTypeOrdinal"),
            ValueLayout.JAVA_INT.withOrder(ByteOrder.LITTLE_ENDIAN).withName("flags"),
            ValueLayout.JAVA_LONG.withOrder(ByteOrder.LITTLE_ENDIAN).withName("occurredAtEpochMs"),
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
     * This method performs zero heap allocations — field offsets are carried by the
     * VarHandle at class-load time; no segment slice is created per call.
     *
     * @param segment    the target segment (non-null, must be writable)
     * @param offset     byte offset within the segment
     * @param descriptor the descriptor to encode (non-null)
     */
    public static void write(MemorySegment segment, long offset, EventDescriptor descriptor) {
        VH_EVENT_ID_HIGH.set(segment, offset, descriptor.eventIdHigh());
        VH_EVENT_ID_LOW.set(segment, offset, descriptor.eventIdLow());
        VH_STREAM_ID_HIGH.set(segment, offset, descriptor.streamIdHigh());
        VH_STREAM_ID_LOW.set(segment, offset, descriptor.streamIdLow());
        VH_EVENT_TYPE_ORDINAL.set(segment, offset, descriptor.eventTypeOrdinal());
        VH_FLAGS.set(segment, offset, descriptor.flags());
        VH_OCCURRED_AT.set(segment, offset, descriptor.occurredAtEpochMs());
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
        return new EventDescriptor(
                (long) VH_EVENT_ID_HIGH.get(segment, offset),
                (long) VH_EVENT_ID_LOW.get(segment, offset),
                (long) VH_STREAM_ID_HIGH.get(segment, offset),
                (long) VH_STREAM_ID_LOW.get(segment, offset),
                (int)  VH_EVENT_TYPE_ORDINAL.get(segment, offset),
                (int)  VH_FLAGS.get(segment, offset),
                (long) VH_OCCURRED_AT.get(segment, offset)
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
