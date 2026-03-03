/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.core.memory;

import eu.exeris.kernel.spi.memory.LoanedBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L1 Unit Tests: {@link AbstractLoanedBuffer} — reference counting, slice arithmetic,
 * boundary enforcement. No off-heap allocation above 1 MB (deterministic, fast).
 *
 * @since 0.5.0
 */
@DisplayName("L1: AbstractLoanedBuffer")
class AbstractLoanedBufferTest {

    // =========================================================================
    // Test double — minimal concrete implementation
    // =========================================================================

    /**
     * Minimal concrete buffer backed by a shared arena (auto-closes on release).
     */
    private static TestBuffer allocate(long bytes) {
        // CHECKSTYLE:OFF — Arena is legal here: this is a test harness allocator
        Arena arena = Arena.ofShared();
        // CHECKSTYLE:ON
        MemorySegment seg = arena.allocate(bytes);
        return new TestBuffer(seg, arena);
    }

    private static final class TestBuffer extends AbstractLoanedBuffer {

        private final MemorySegment seg;
        private final Arena arena;
        final AtomicBoolean released = new AtomicBoolean(false);

        TestBuffer(MemorySegment seg, Arena arena) {
            super();
            this.seg = seg;
            this.arena = arena;
            setSize(seg.byteSize());
        }

        @Override
        protected MemorySegment backingSegment() {
            return seg;
        }

        @Override
        protected void onRelease() {
            released.set(true);
            arena.close();
        }
    }

    // =========================================================================
    // Nested: Reference Counting
    // =========================================================================

    @Nested
    @DisplayName("Reference Counting")
    class ReferenceCounting {

        @Test
        @DisplayName("Initial refCount is 1")
        void initialRefCountIsOne() {
            try (TestBuffer buf = allocate(64)) {
                assertThat(buf.refCount()).isEqualTo(1);
            }
        }

        @Test
        @DisplayName("retain() increments refCount")
        void retainIncrementsRefCount() {
            TestBuffer buf = allocate(64);
            buf.retain();
            assertThat(buf.refCount()).isEqualTo(2);
            buf.close();
            buf.close();
            assertThat(buf.released.get()).isTrue();
        }

        @Test
        @DisplayName("close() decrements refCount and triggers release at 0")
        void closeTriggesReleaseAtZero() {
            TestBuffer buf = allocate(64);
            assertThat(buf.released.get()).isFalse();
            buf.close();
            assertThat(buf.released.get()).isTrue();
            assertThat(buf.refCount()).isZero();
        }

        @Test
        @DisplayName("Double close is idempotent — does not release twice")
        void doubleCloseIsIdempotent() {
            TestBuffer buf = allocate(64);
            buf.close();
            assertThat(buf.released.get()).isTrue();
            buf.close();
            assertThat(buf.refCount()).isZero();
        }

        @Test
        @DisplayName("retain() on dead buffer throws IllegalStateException")
        void retainOnDeadBufferThrows() {
            TestBuffer buf = allocate(64);
            buf.close();
            assertThatThrownBy(buf::retain).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("Close action fires LIFO when refCount reaches 0")
        void closeActionFiresLifo() {
            TestBuffer buf = allocate(64);
            StringBuilder order = new StringBuilder();
            buf.addCloseAction(() -> order.append("A"));
            buf.addCloseAction(() -> order.append("B"));
            buf.close();
            assertThat(order).hasToString("BA");
        }
    }

    // =========================================================================
    // Nested: Slice Arithmetic
    // =========================================================================

    @Nested
    @DisplayName("Slice Arithmetic")
    class SliceArithmetic {

        @Test
        @DisplayName("slice() returns segment with correct offset and length")
        void sliceHasCorrectOffsetAndLength() {
            TestBuffer buf = allocate(256);
            buf.segment().set(ValueLayout.JAVA_LONG, 64, 0xCAFEBABEL);

            try (LoanedBuffer slice = buf.slice(64, 8)) {
                assertThat(slice.size()).isEqualTo(8);
                long value = slice.segment().get(ValueLayout.JAVA_LONG, 0);
                assertThat(value).isEqualTo(0xCAFEBABEL);
            }
            buf.close();
        }

        @Test
        @DisplayName("slice() increments parent refCount — parent stays alive until slice closes")
        void sliceKeepsParentAlive() {
            TestBuffer buf = allocate(128);
            LoanedBuffer slice = buf.slice(0, 64);
            assertThat(buf.refCount()).isEqualTo(2);

            buf.close();
            assertThat(buf.released.get()).isFalse();

            slice.close();
            assertThat(buf.released.get()).isTrue();
        }

        @Test
        @DisplayName("peek() returns zero-copy view WITHOUT incrementing refCount")
        void peekDoesNotIncrementRefCount() {
            TestBuffer buf = allocate(128);
            assertThat(buf.refCount()).isEqualTo(1);

            try (LoanedBuffer peek = buf.peek(0, 64)) {
                assertThat(buf.refCount()).isEqualTo(1);
                assertThat(peek.size()).isEqualTo(64);
            }
            buf.close();
        }

        @Test
        @DisplayName("Nested slices: grandchild holds grandparent alive")
        void nestedSlicesChainRefCounts() {
            TestBuffer buf = allocate(256);
            LoanedBuffer child = buf.slice(0, 128);
            LoanedBuffer grandchild = child.slice(0, 64);

            buf.close();
            assertThat(buf.released.get()).isFalse();

            child.close();
            assertThat(buf.released.get()).isFalse();

            grandchild.close();
            assertThat(buf.released.get()).isTrue();
        }
    }

    // =========================================================================
    // Nested: Boundary Enforcement (Panama Segfault Shield — L1 part)
    // =========================================================================

    @Nested
    @DisplayName("Boundary Enforcement")
    class BoundaryEnforcement {

        @Test
        @DisplayName("segment() on closed buffer throws IllegalStateException")
        void segmentOnClosedBufferThrows() {
            TestBuffer buf = allocate(64);
            buf.close();
            assertThatThrownBy(buf::segment).isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("setSize() beyond capacity throws IllegalArgumentException")
        void setSizeBeyondCapacityThrows() {
            TestBuffer buf = allocate(64);
            assertThatThrownBy(() -> buf.setSize(65))
                    .isInstanceOf(IllegalArgumentException.class);
            buf.close();
        }

        @Test
        @DisplayName("setSize() to negative value throws IllegalArgumentException")
        void setSizeNegativeThrows() {
            TestBuffer buf = allocate(64);
            assertThatThrownBy(() -> buf.setSize(-1))
                    .isInstanceOf(IllegalArgumentException.class);
            buf.close();
        }

        @Test
        @DisplayName("addCloseAction() on dead buffer throws IllegalStateException")
        void addCloseActionOnDeadBufferThrows() {
            TestBuffer buf = allocate(64);
            buf.close();
            assertThatThrownBy(() -> buf.addCloseAction(() -> {
            }))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("Out-of-bounds write via Panama FFM throws IndexOutOfBoundsException — not Segfault")
        void outOfBoundsWriteThrowsNotSegfault() {
            TestBuffer buf = allocate(64);
            MemorySegment seg = buf.segment();
            assertThatThrownBy(() -> seg.set(ValueLayout.JAVA_LONG, 60, 0xDEADL))
                    .isInstanceOf(IndexOutOfBoundsException.class);
            buf.close();
        }
    }

    // =========================================================================
    // Nested: Close Action Safety
    // =========================================================================

    @Nested
    @DisplayName("Close Action Safety")
    class CloseActionSafety {

        @Test
        @DisplayName("Close action throwing RuntimeException does not crash releasing thread")
        void closeActionExceptionIsSuppressedViaJfr() {
            TestBuffer buf = allocate(64);
            buf.addCloseAction(() -> {
                throw new RuntimeException("Simulated cleanup failure");
            });
            buf.close();
            assertThat(buf.released.get()).isTrue();
        }
    }
}
