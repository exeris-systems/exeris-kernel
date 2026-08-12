/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.storage;

import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.storage.blob.BlobMetadata;
import eu.exeris.kernel.spi.storage.blob.BlobRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What the handle is allowed to read, when HEAD and GET disagree.
 *
 * <h2>Why a mocked buffer rather than a real download</h2>
 * <p>The defect needs a response buffer whose slot is larger than the bytes this response wrote, and
 * whose surplus holds something recognisable. That is the ordinary state of a pooled buffer — the
 * allocator hands back a slot the previous response used — but it is not a state a store can be asked
 * to produce on demand. Stubbing {@code segment()} and {@code size()} apart is the shortest way to
 * state the only thing that matters: {@code segment()} spans the slot, {@code size()} spans this
 * response, and the difference is another caller's data.
 */
@DisplayName("CommunityS3DownloadHandle — the GET bounds the read, not the HEAD")
class CommunityS3DownloadHandleTest {

    private static final int SLOT_BYTES = 64;
    private static final int DELIVERED_BYTES = 4;
    /** What HEAD promised — larger than the GET delivered, which is the whole point. */
    private static final int HEAD_BYTES = 16;
    private static final byte RESIDUE = (byte) 0xEE;

    private static BlobMetadata metadata() {
        return new BlobMetadata(new BlobRef("container", "key"), HEAD_BYTES,
                BlobMetadata.DEFAULT_CONTENT_TYPE);
    }

    @Test
    @DisplayName("a GET shorter than the HEAD size stops at the delivered bytes")
    void shortResponseDoesNotReadPastTheDeliveredBytes() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment slot = arena.allocate(SLOT_BYTES);
            // The previous borrower of this pooled slot.
            slot.fill(RESIDUE);
            for (int i = 0; i < DELIVERED_BYTES; i++) {
                slot.set(ValueLayout.JAVA_BYTE, i, (byte) (i + 1));
            }

            LoanedBuffer body = mock(LoanedBuffer.class);
            when(body.segment()).thenReturn(slot);
            when(body.size()).thenReturn((long) DELIVERED_BYTES);

            MemorySegment target = arena.allocate(SLOT_BYTES);
            target.fill((byte) 0);

            CommunityS3DownloadHandle handle =
                    new CommunityS3DownloadHandle(metadata(), body, 0L, HEAD_BYTES);

            int read = handle.read(target, HEAD_BYTES);

            assertThat(read)
                    .as("the object was overwritten smaller between HEAD and GET; the handle may "
                        + "only hand out what this response actually delivered")
                    .isEqualTo(DELIVERED_BYTES);
            assertThat(target.get(ValueLayout.JAVA_BYTE, DELIVERED_BYTES))
                    .as("byte %d came from the pooled slot, not from this response — copying it "
                        + "returns the previous response's body as this object's content",
                            DELIVERED_BYTES)
                    .isNotEqualTo(RESIDUE);
            assertThat(handle.read(target, HEAD_BYTES))
                    .as("and the handle is then exhausted")
                    .isEqualTo(-1);
        }
    }

    @Test
    @DisplayName("a whole-object answer to a ranged request cannot start past what arrived")
    void startBeyondTheDeliveredBytesReadsNothing() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment slot = arena.allocate(SLOT_BYTES);
            slot.fill(RESIDUE);

            LoanedBuffer body = mock(LoanedBuffer.class);
            when(body.size()).thenReturn((long) DELIVERED_BYTES);

            MemorySegment target = arena.allocate(SLOT_BYTES);

            // A store that ignores Range answers 200 with the whole object, so the slice starts at
            // its own offset. If the object is now shorter than that offset, there is nothing at it.
            CommunityS3DownloadHandle handle =
                    new CommunityS3DownloadHandle(metadata(), body, DELIVERED_BYTES + 2L, HEAD_BYTES);

            assertThat(handle.read(target, HEAD_BYTES))
                    .as("an offset past the delivered bytes is end-of-stream, not a window onto "
                        + "the rest of the slot")
                    .isEqualTo(-1);
        }
    }

    @Test
    @DisplayName("a response that filled its slot still reads in full")
    void fullResponseIsUnaffected() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment slot = arena.allocate(SLOT_BYTES);
            for (int i = 0; i < HEAD_BYTES; i++) {
                slot.set(ValueLayout.JAVA_BYTE, i, (byte) (i + 1));
            }

            LoanedBuffer body = mock(LoanedBuffer.class);
            when(body.segment()).thenReturn(slot);
            when(body.size()).thenReturn((long) HEAD_BYTES);

            MemorySegment target = arena.allocate(SLOT_BYTES);
            CommunityS3DownloadHandle handle =
                    new CommunityS3DownloadHandle(metadata(), body, 0L, HEAD_BYTES);

            assertThat(handle.read(target, HEAD_BYTES))
                    .as("the clamp must not shorten a response that delivered what it promised")
                    .isEqualTo(HEAD_BYTES);
            assertThat(target.get(ValueLayout.JAVA_BYTE, HEAD_BYTES - 1))
                    .isEqualTo((byte) HEAD_BYTES);
        }
    }
}
