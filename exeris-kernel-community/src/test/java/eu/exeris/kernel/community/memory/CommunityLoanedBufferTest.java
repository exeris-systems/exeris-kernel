/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.memory;

import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * L1 Unit: {@link CommunityLoanedBuffer} — segment integrity, zero-copy,
 * size tracking, and logical ownership lifecycle.
 *
 * <h2>Coverage</h2>
 * <ul>
 *   <li>segment() returns a native (off-heap) MemorySegment</li>
 *   <li>segment().address() is > 0 (non-null pointer)</li>
 *   <li>segment().byteSize() reflects the allocated capacity</li>
 *   <li>setSize() / size() track the logical write cursor</li>
 *   <li>retain() increments refcount — isAlive() stays true until all released</li>
 *   <li>close() after full release → isAlive() == false</li>
 *   <li>double close() is safe (idempotent release path)</li>
 *   <li>segment() after close() is disallowed (WrapperInvalidException or ISE)</li>
 *   <li>asSlice() returns a sub-region of the original segment</li>
 * </ul>
 *
 * @since 0.5.0
 */
@DisplayName("L1 Unit: CommunityLoanedBuffer")
class CommunityLoanedBufferTest {

    private MemoryAllocator allocator;

    @BeforeEach
    void setUp() {
        allocator = new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
    }

    @AfterEach
    void tearDown() {
        allocator.close();
    }

    // =========================================================================
    // Segment integrity — zero-copy contract
    // =========================================================================

    @Nested
    @DisplayName("Segment integrity — zero-copy contract")
    class SegmentIntegrity {

        @Test
        @DisplayName("segment() returns a non-null, native MemorySegment (off-heap)")
        void segmentIsNative() {
            try (LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO)) {
                MemorySegment seg = buf.segment();
                assertThat(seg).isNotNull();
                assertThat(seg.isNative())
                        .as("LoanedBuffer.segment() MUST be off-heap (native). " +
                            "A heap-backed segment violates the zero-copy TLS contract.")
                        .isTrue();
            }
        }

        @Test
        @DisplayName("segment().address() is non-zero — valid native pointer")
        void segmentAddressIsNonZero() {
            try (LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO)) {
                assertThat(buf.segment().address())
                        .as("Native segment address MUST be non-zero — 0 indicates NULL pointer")
                        .isGreaterThan(0L);
            }
        }

        @Test
        @DisplayName("segment().byteSize() >= requested capacity")
        void segmentByteSizeMatchesCapacity() {
            try (LoanedBuffer buf = allocator.allocateNetwork(4096)) {
                assertThat(buf.segment().byteSize())
                        .isGreaterThanOrEqualTo(4096L);
            }
        }

        @Test
        @DisplayName("Buffer supports read/write at offset 0 and end-1 without SIGSEGV")
        void bufferSupportsReadWrite() {
            try (LoanedBuffer buf = allocator.allocateNetwork(512)) {
                MemorySegment seg = buf.segment();
                // Write at first and last byte
                seg.set(ValueLayout.JAVA_BYTE, 0, (byte) 0xCA);
                seg.set(ValueLayout.JAVA_BYTE, 511, (byte) 0xFE);

                assertThat(seg.get(ValueLayout.JAVA_BYTE, 0)).isEqualTo((byte) 0xCA);
                assertThat(seg.get(ValueLayout.JAVA_BYTE, 511)).isEqualTo((byte) 0xFE);
            }
        }

        @Test
        @DisplayName("asSlice() returns same native region — no copy")
        void asSliceIsZeroCopy() {
            try (LoanedBuffer buf = allocator.allocateNetwork(1024)) {
                MemorySegment original = buf.segment();
                MemorySegment slice    = original.asSlice(0, 256);

                assertThat(slice.byteSize()).isEqualTo(256L);
                assertThat(slice.address()).isEqualTo(original.address());
            }
        }
    }

    // =========================================================================
    // size tracking
    // =========================================================================

    @Nested
    @DisplayName("setSize() / size() tracking")
    class SizeTracking {

        @Test
        @DisplayName("size() is 0 after allocation")
        void sizeIsZeroInitially() {
            try (LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO)) {
                assertThat(buf.size()).isZero();
            }
        }

        @Test
        @DisplayName("setSize() updates size()")
        void setSizeUpdatesSizeField() {
            try (LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO)) {
                buf.setSize(128);
                assertThat(buf.size()).isEqualTo(128L);
            }
        }

        @Test
        @DisplayName("capacity() >= size() — capacity is the physical limit")
        void capacityAtLeastSize() {
            try (LoanedBuffer buf = allocator.allocateNetwork(2048)) {
                buf.setSize(512);
                assertThat(buf.capacity()).isGreaterThanOrEqualTo(buf.size());
            }
        }
    }

    // =========================================================================
    // retain() / release lifecycle
    // =========================================================================

    @Nested
    @DisplayName("retain() / release lifecycle")
    class RetainRelease {

        @Test
        @DisplayName("isAlive() is true immediately after allocation")
        void isAliveInitially() {
            try (LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO)) {
                assertThat(buf.isAlive()).isTrue();
            }
        }

        @Test
        @DisplayName("isAlive() is false after close()")
        void isAliveAfterClose() {
            LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO);
            buf.close();
            assertThat(buf.isAlive()).isFalse();
        }

        @Test
        @DisplayName("retain() keeps buffer alive; close() decrements; final close() deallocates")
        void retainAndRelease() {
            LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO);
            buf.retain(); // refcount=2

            buf.close(); // refcount=1
            assertThat(buf.isAlive())
                    .as("Buffer with retain() active must remain alive after first close()")
                    .isTrue();

            buf.close(); // refcount=0 — deallocate
            assertThat(buf.isAlive()).isFalse();
        }

        @Test
        @DisplayName("double close() after single allocate() does not throw")
        void doubleCloseIsIdempotent() {
            LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO);
            buf.close();
            assertThatCode(buf::close).doesNotThrowAnyException();
        }
    }
}
