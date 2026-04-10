/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.core.events;

import eu.exeris.kernel.core.events.serialization.EventDescriptorCodec;
import eu.exeris.kernel.spi.events.EventDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit: {@link EventDescriptorCodec} — zero-copy binary round-trip.
 *
 * @since 0.5.0
 */
@DisplayName("EventDescriptorCodec — binary round-trip")
class EventDescriptorCodecTest {

    @Test
    @DisplayName("write() then read() returns identical descriptor (all fields)")
    void roundTripPreservesAllFields() {
        UUID id       = UUID.randomUUID();
        UUID streamId = UUID.randomUUID();
        EventDescriptor original = new EventDescriptor(
                id.getMostSignificantBits(), id.getLeastSignificantBits(),
                streamId.getMostSignificantBits(), streamId.getLeastSignificantBits(),
                42, EventDescriptor.FLAG_PERSISTENT | EventDescriptor.FLAG_ASYNC,
                System.currentTimeMillis());

        // CHECKSTYLE:OFF: DirectArenaAllocation — test-only, no approved allocator in test scope
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(EventDescriptorCodec.WIRE_SIZE);
            EventDescriptorCodec.write(seg, 0L, original);
            EventDescriptor decoded = EventDescriptorCodec.read(seg, 0L);

            assertThat(decoded.eventIdHigh()).isEqualTo(original.eventIdHigh());
            assertThat(decoded.eventIdLow()).isEqualTo(original.eventIdLow());
            assertThat(decoded.streamIdHigh()).isEqualTo(original.streamIdHigh());
            assertThat(decoded.streamIdLow()).isEqualTo(original.streamIdLow());
            assertThat(decoded.eventTypeOrdinal()).isEqualTo(original.eventTypeOrdinal());
            assertThat(decoded.flags()).isEqualTo(original.flags());
            assertThat(decoded.occurredAtEpochMs()).isEqualTo(original.occurredAtEpochMs());
        }
        // CHECKSTYLE:ON: DirectArenaAllocation
    }

    @Test
    @DisplayName("write() at non-zero offset uses correct slice")
    void roundTripAtOffset() {
        long offset = 128L;
        EventDescriptor original = new EventDescriptor(
                1L, 2L, 3L, 4L, 7, EventDescriptor.FLAG_ORDERED, 999_000L);

        // CHECKSTYLE:OFF: DirectArenaAllocation — test-only, no approved allocator in test scope
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment seg = arena.allocate(offset + EventDescriptorCodec.WIRE_SIZE);
            EventDescriptorCodec.write(seg, offset, original);
            EventDescriptor decoded = EventDescriptorCodec.read(seg, offset);

            assertThat(decoded).isEqualTo(original);
        }
        // CHECKSTYLE:ON: DirectArenaAllocation
    }

    @Test
    @DisplayName("WIRE_SIZE is exactly 64 bytes (one CPU cache line)")
    void wireSizeIs64() {
        assertThat(EventDescriptorCodec.WIRE_SIZE).isEqualTo(64);
    }

    @Test
    @DisplayName("layout() byte size matches WIRE_SIZE")
    void layoutByteSize() {
        assertThat(EventDescriptorCodec.layout().byteSize()).isEqualTo(EventDescriptorCodec.WIRE_SIZE);
    }
}
