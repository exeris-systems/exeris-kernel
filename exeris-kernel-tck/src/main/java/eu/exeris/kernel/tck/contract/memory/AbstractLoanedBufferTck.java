/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.tck.contract.memory;

import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.lang.foreign.ValueLayout;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCK: Abstract base for {@link LoanedBuffer} contract verification.
 *
 * <h2>TCK Pattern</h2>
 * <p>This class defines the <em>immutable contract</em> that every {@link LoanedBuffer}
 * implementation MUST satisfy. It is driven through a {@link MemoryAllocator} factory —
 * the TCK has zero knowledge of {@code Community}, {@code Enterprise}, or
 * {@code AbstractLoanedBuffer}. It imports only from {@code exeris-kernel-spi}.
 *
 * <h2>The Wall (SPI Compliance)</h2>
 * <p>This class MUST ONLY import from {@code exeris-kernel-spi}. It must never import
 * {@code eu.exeris.kernel.core.*}, {@code eu.exeris.kernel.community.*}, or
 * {@code eu.exeris.kernel.enterprise.*}.
 *
 * <h2>How to use</h2>
 * <pre>{@code
 * class CommunityLoanedBufferTckTest extends AbstractLoanedBufferTck {
 *     \@Override
 *     protected MemoryAllocator createAllocator() {
 *         return new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
 *     }
 * }
 * }</pre>
 *
 * @see AbstractMemoryAllocatorTck
 * @see LoanedBuffer
 * @since 0.5.0
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public abstract class AbstractLoanedBufferTck {

    // =========================================================================
    // Factory method — subclass provides the allocator under test
    // =========================================================================

    /**
     * Creates the {@link MemoryAllocator} used to obtain {@link LoanedBuffer} instances
     * under test.
     *
     * @return allocator; must not be {@code null}
     */
    protected abstract MemoryAllocator createAllocator();

    private MemoryAllocator allocator;

    @BeforeEach
    final void setUpAllocator() {
        allocator = createAllocator();
    }

    @AfterEach
    final void tearDownAllocator() {
        allocator.close();
    }

    // =========================================================================
    // L1: Lifecycle & Reference Counting
    // =========================================================================

    @Nested
    @DisplayName("Lifecycle & Reference Counting")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class LifecycleAndRefCounting {

        @Test
        @Order(1)
        @DisplayName("Initial refCount is 1 after allocation")
        void initialRefCountIsOne() {
            try (LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO)) {
                assertThat(buf.refCount()).isEqualTo(1);
                assertThat(buf.isAlive()).isTrue();
            }
        }

        @Test
        @Order(2)
        @DisplayName("retain() increments refCount; both close() calls needed to release")
        void retainIncrements() {
            LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO);
            assertThat(buf.refCount()).isEqualTo(1);
            buf.retain();
            assertThat(buf.refCount()).isEqualTo(2);
            buf.close();
            assertThat(buf.isAlive()).isTrue();
            buf.close();
            assertThat(buf.isAlive()).isFalse();
            assertThat(buf.refCount()).isZero();
        }

        @Test
        @Order(3)
        @DisplayName("retain() on an already-released buffer throws IllegalStateException")
        void retainOnReleasedThrows() {
            LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO);
            buf.close();
            assertThatThrownBy(buf::retain)
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @Order(4)
        @DisplayName("close() is idempotent — multiple calls do not throw")
        void closeIsIdempotent() {
            LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO);
            buf.close();
            buf.close();
            assertThat(buf.refCount()).isZero();
        }

        @Test
        @Order(5)
        @DisplayName("segment() after close() throws IllegalStateException")
        void segmentAfterCloseThrows() {
            LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO);
            buf.close();
            assertThatThrownBy(buf::segment)
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    // =========================================================================
    // L2: Size Management
    // =========================================================================

    @Nested
    @DisplayName("Size Management")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class SizeManagement {

        @Test
        @Order(1)
        @DisplayName("capacity() >= requested size from AllocationHint.MICRO")
        void capacityIsAtLeastHintSize() {
            try (LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO)) {
                assertThat(buf.capacity())
                        .isGreaterThanOrEqualTo(AllocationHint.MICRO.sizeBytes());
            }
        }

        @Test
        @Order(2)
        @DisplayName("setSize() updates size(); value is in [0, capacity()]")
        void setSizeUpdatesSize() {
            try (LoanedBuffer buf = allocator.allocate(AllocationHint.SMALL)) {
                long cap = buf.capacity();
                buf.setSize(cap / 2);
                assertThat(buf.size()).isEqualTo(cap / 2);
                buf.setSize(0);
                assertThat(buf.size()).isZero();
                buf.setSize(cap);
                assertThat(buf.size()).isEqualTo(cap);
            }
        }

        @Test
        @Order(3)
        @DisplayName("setSize() with negative value throws IllegalArgumentException")
        void setSizeNegativeThrows() {
            try (LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO)) {
                assertThatThrownBy(() -> buf.setSize(-1L))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }

        @Test
        @Order(4)
        @DisplayName("setSize() with value > capacity() throws IllegalArgumentException")
        void setSizeExceedsCapacityThrows() {
            try (LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO)) {
                long overCapacity = buf.capacity() + 1;
                assertThatThrownBy(() -> buf.setSize(overCapacity))
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    // =========================================================================
    // L3: Zero-Copy Fragmentation — slice()
    // =========================================================================

    @Nested
    @DisplayName("Zero-Copy Fragmentation — slice()")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class SliceContract {

        @Test
        @Order(1)
        @DisplayName("slice() returns a view into the same backing memory (no copy)")
        void sliceSharesBackingMemory() {
            try (LoanedBuffer parent = allocator.allocate(AllocationHint.SMALL)) {
                parent.segment().set(ValueLayout.JAVA_INT, 0, 0xDEAD_BEEF);

                try (LoanedBuffer slice = parent.slice(0, 4)) {
                    int read = slice.segment().get(ValueLayout.JAVA_INT, 0);
                    assertThat(read).isEqualTo(0xDEAD_BEEF);
                }
            }
        }

        @Test
        @Order(2)
        @DisplayName("slice() increments parent refCount; parent stays alive while slice is live")
        void sliceIncrementsParentRefCount() {
            LoanedBuffer parent = allocator.allocate(AllocationHint.SMALL);
            assertThat(parent.refCount()).isEqualTo(1);

            LoanedBuffer slice = parent.slice(0, 8);
            assertThat(parent.refCount()).isEqualTo(2);

            parent.close();
            assertThat(parent.isAlive()).isTrue();
            assertThat(parent.refCount()).isEqualTo(1);

            slice.close();
            assertThat(parent.isAlive()).isFalse();
        }

        @Test
        @Order(3)
        @DisplayName("slice() capacity() equals the requested length")
        void sliceCapacityEqualsLength() {
            try (LoanedBuffer parent = allocator.allocate(AllocationHint.SMALL)) {
                long sliceLen = 128L;
                try (LoanedBuffer slice = parent.slice(0, sliceLen)) {
                    assertThat(slice.capacity()).isEqualTo(sliceLen);
                }
            }
        }

        @Test
        @Order(4)
        @DisplayName("slice() with offset+length > capacity() throws IndexOutOfBoundsException")
        void sliceOutOfBoundsThrows() {
            try (LoanedBuffer parent = allocator.allocate(AllocationHint.MICRO)) {
                long cap = parent.capacity();
                assertThatThrownBy(() -> parent.slice(0, cap + 1))
                        .isInstanceOf(IndexOutOfBoundsException.class);
            }
        }

        @Test
        @Order(5)
        @DisplayName("Two non-overlapping slices carry independent data (no aliasing)")
        void nonOverlappingSlicesAreIsolated() {
            try (LoanedBuffer parent = allocator.allocate(AllocationHint.SMALL)) {
                try (LoanedBuffer sliceA = parent.slice(0, 8);
                     LoanedBuffer sliceB = parent.slice(8, 8)) {
                    sliceA.segment().set(ValueLayout.JAVA_LONG, 0, 0xAAAA_BBBB_CCCC_DDDDL);
                    sliceB.segment().set(ValueLayout.JAVA_LONG, 0, 0x1111_2222_3333_4444L);
                    assertThat(sliceA.segment().get(ValueLayout.JAVA_LONG, 0))
                            .isEqualTo(0xAAAA_BBBB_CCCC_DDDDL);
                    assertThat(sliceB.segment().get(ValueLayout.JAVA_LONG, 0))
                            .isEqualTo(0x1111_2222_3333_4444L);
                }
            }
        }
    }

    // =========================================================================
    // L4: Zero-Copy Fragmentation — view()
    // =========================================================================

    @Nested
    @DisplayName("Zero-Copy Fragmentation — view()")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class ViewContract {

        @Test
        @Order(1)
        @DisplayName("view() returns a read-only view covering size() bytes")
        void viewCoversValidBytes() {
            try (LoanedBuffer parent = allocator.allocate(AllocationHint.SMALL)) {
                long written = 32L;
                parent.setSize(written);
                parent.segment().set(ValueLayout.JAVA_LONG, 0, 0xCAFE_BABE_DEAD_BEEFL);
                try (LoanedBuffer v = parent.view()) {
                    assertThat(v.size()).isEqualTo(written);
                    long read = v.segment().get(ValueLayout.JAVA_LONG, 0);
                    assertThat(read).isEqualTo(0xCAFE_BABE_DEAD_BEEFL);
                }
            }
        }

        @Test
        @Order(2)
        @DisplayName("view() increments parent refCount; parent stays alive while view is live")
        void viewIncrementsParentRefCount() {
            LoanedBuffer parent = allocator.allocate(AllocationHint.SMALL);
            parent.setSize(16L);
            LoanedBuffer v = parent.view();
            assertThat(parent.refCount()).isEqualTo(2);
            parent.close();
            assertThat(parent.isAlive()).isTrue();
            v.close();
            assertThat(parent.isAlive()).isFalse();
        }
    }

    // =========================================================================
    // L5: Non-Owning Peek View — peek()
    // =========================================================================

    @Nested
    @DisplayName("Non-Owning Peek View — peek()")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class PeekContract {

        @Test
        @Order(1)
        @DisplayName("peek() does NOT increment parent refCount")
        void peekDoesNotIncrementRefCount() {
            try (LoanedBuffer parent = allocator.allocate(AllocationHint.SMALL)) {
                int beforePeek = parent.refCount();
                try (LoanedBuffer peekView = parent.peek(0, 8)) {
                    assertThat(parent.refCount()).isEqualTo(beforePeek);
                    peekView.capacity();
                }
                assertThat(parent.refCount()).isEqualTo(beforePeek);
            }
        }

        @Test
        @Order(2)
        @DisplayName("peek() close() does not decrement parent refCount")
        void peekCloseIsRefCountNoop() {
            LoanedBuffer parent = allocator.allocate(AllocationHint.SMALL);
            LoanedBuffer peekView = parent.peek(0, 8);
            peekView.close();
            assertThat(parent.refCount()).isEqualTo(1);
            parent.close();
            assertThat(parent.isAlive()).isFalse();
        }

        @Test
        @Order(3)
        @DisplayName("peek() exposes the correct memory range")
        void peekExposesCorrectRange() {
            try (LoanedBuffer parent = allocator.allocate(AllocationHint.SMALL)) {
                parent.segment().set(ValueLayout.JAVA_INT, 16, 0x1234_5678);
                try (LoanedBuffer peekView = parent.peek(16, 4)) {
                    assertThat(peekView.segment().get(ValueLayout.JAVA_INT, 0))
                            .isEqualTo(0x1234_5678);
                }
            }
        }
    }

    // =========================================================================
    // L6: Close Actions
    // =========================================================================

    @Nested
    @DisplayName("Close Actions")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class CloseActions {

        @Test
        @Order(1)
        @DisplayName("addCloseAction() callback is invoked exactly once when refCount reaches 0")
        void closeActionInvokedOnRelease() {
            LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO);
            int[] callCount = {0};
            buf.addCloseAction(() -> callCount[0]++);
            buf.close();
            assertThat(callCount[0]).isEqualTo(1);
        }

        @Test
        @Order(2)
        @DisplayName("addCloseAction() callback is NOT invoked when refCount only decrements to 1")
        void closeActionNotInvokedWhileRetained() {
            LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO);
            int[] callCount = {0};
            buf.addCloseAction(() -> callCount[0]++);
            buf.retain();
            buf.close();
            assertThat(callCount[0]).isZero();
            buf.close();
            assertThat(callCount[0]).isEqualTo(1);
        }

        @Test
        @Order(3)
        @DisplayName("addCloseAction() callback firing after close does not invoke again on idempotent close")
        void idempotentCloseDoesNotDoubleFireAction() {
            LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO);
            int[] callCount = {0};
            buf.addCloseAction(() -> callCount[0]++);
            buf.close();
            buf.close();
            assertThat(callCount[0]).isEqualTo(1);
        }
    }

    // =========================================================================
    // L7: Liveness
    // =========================================================================

    @Nested
    @DisplayName("Liveness checks")
    class LivenessChecks {

        @Test
        @Order(1)
        @DisplayName("isAlive() returns true while refCount > 0, false after full release")
        void isAliveReflectsRefCount() {
            LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO);
            buf.retain();
            assertThat(buf.isAlive()).isTrue();
            buf.close();
            assertThat(buf.isAlive()).isTrue();
            buf.close();
            assertThat(buf.isAlive()).isFalse();
        }
    }

    // =========================================================================
    // L8: JMM Visibility — happens-before guarantee on close-action slots
    // =========================================================================

    @Nested
    @DisplayName("JMM Visibility — close-action happens-before")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class JmmVisibility {

        @Test
        @Order(1)
        @DisplayName("Close action registered by thread A is visible to releasing thread B")
        void closeActionCrossThreadVisibility() throws InterruptedException {
            LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO);
            buf.retain();

            CountDownLatch actionRegistered = new CountDownLatch(1);
            CountDownLatch releaseDone = new CountDownLatch(1);
            AtomicBoolean actionFired = new AtomicBoolean(false);
            AtomicReference<Throwable> threadError = new AtomicReference<>();

            Thread registrar = Thread.ofVirtual().start(() -> {
                try {
                    buf.addCloseAction(() -> actionFired.set(true));
                    actionRegistered.countDown();
                } catch (Throwable t) {
                    threadError.set(t);
                    actionRegistered.countDown();
                }
            });

            Thread releaser = Thread.ofVirtual().start(() -> {
                try {
                    actionRegistered.await();
                    buf.close();
                    buf.close();
                    releaseDone.countDown();
                } catch (Throwable t) {
                    threadError.set(t);
                    releaseDone.countDown();
                }
            });

            releaseDone.await();
            registrar.join();
            releaser.join();

            assertThat(threadError.get()).isNull();
            assertThat(actionFired.get()).isTrue();
        }

        @Test
        @Order(2)
        @DisplayName("Multiple close-action slots registered cross-thread are all fired at release")
        void multipleSlotsCrossThreadVisibility() throws InterruptedException {
            LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO);
            buf.retain();

            CountDownLatch allRegistered = new CountDownLatch(1);
            int[] callCount = {0};

            Thread registrar = Thread.ofVirtual().start(() -> {
                buf.addCloseAction(() -> callCount[0]++);
                buf.addCloseAction(() -> callCount[0]++);
                allRegistered.countDown();
            });

            allRegistered.await();
            buf.close();
            buf.close();
            registrar.join();

            assertThat(callCount[0]).isEqualTo(2);
        }
    }

    // =========================================================================
    // L9: Peek View Misuse — EX-MEM-1003 contract
    // =========================================================================

    @Nested
    @DisplayName("Peek View Misuse — EX-MEM-1003 contract")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class PeekViewMisuseContract {

        @Test
        @Order(1)
        @DisplayName("retain() on peek view does not change parent refCount")
        void retainOnPeekIsRefCountNoop() {
            try (LoanedBuffer parent = allocator.allocate(AllocationHint.SMALL)) {
                try (LoanedBuffer peekView = parent.peek(0, 8)) {
                    int before = parent.refCount();
                    peekView.retain();
                    assertThat(parent.refCount()).isEqualTo(before);
                }
            }
        }

        @Test
        @Order(2)
        @DisplayName("addCloseAction() on peek view throws UnsupportedOperationException")
        void addCloseActionOnPeekViewThrows() {
            try (LoanedBuffer parent = allocator.allocate(AllocationHint.SMALL)) {
                try (LoanedBuffer peekView = parent.peek(0, 8)) {
                    assertThatThrownBy(() -> peekView.addCloseAction(() -> {}))
                            .isInstanceOf(UnsupportedOperationException.class);
                }
            }
        }

        @Test
        @Order(3)
        @DisplayName("close() on peek view does not release parent")
        void closeOnPeekDoesNotReleaseParent() {
            LoanedBuffer parent = allocator.allocate(AllocationHint.SMALL);
            LoanedBuffer peekView = parent.peek(0, 8);
            peekView.close();
            assertThat(parent.isAlive()).isTrue();
            assertThat(parent.refCount()).isEqualTo(1);
            parent.close();
            assertThat(parent.isAlive()).isFalse();
        }
    }

    // =========================================================================
    // L10: Slice Ownership Isolation — independent close-action slots
    // =========================================================================

    @Nested
    @DisplayName("Slice Ownership Isolation")
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class SliceOwnershipIsolation {

        @Test
        @Order(1)
        @DisplayName("Close action on slice fires on slice-close, NOT on parent-close")
        void sliceCloseActionIsIsolatedFromParent() {
            LoanedBuffer parent = allocator.allocate(AllocationHint.SMALL);
            AtomicBoolean sliceActionFired = new AtomicBoolean(false);
            AtomicBoolean parentActionFired = new AtomicBoolean(false);

            parent.addCloseAction(() -> parentActionFired.set(true));
            LoanedBuffer slice = parent.slice(0, 8);
            slice.addCloseAction(() -> sliceActionFired.set(true));

            parent.close();
            assertThat(parentActionFired.get()).isFalse();
            assertThat(sliceActionFired.get()).isFalse();

            slice.close();
            assertThat(sliceActionFired.get()).isTrue();
            assertThat(parentActionFired.get()).isTrue();
        }

        @Test
        @Order(2)
        @DisplayName("Parent close action is blocked by a live slice")
        void parentCloseActionBlockedByLiveSlice() {
            LoanedBuffer parent = allocator.allocate(AllocationHint.SMALL);
            AtomicBoolean parentActionFired = new AtomicBoolean(false);
            parent.addCloseAction(() -> parentActionFired.set(true));

            LoanedBuffer slice = parent.slice(0, 8);
            parent.close();
            assertThat(parentActionFired.get()).isFalse();
            assertThat(parent.isAlive()).isTrue();

            slice.close();
            assertThat(parentActionFired.get()).isTrue();
            assertThat(parent.isAlive()).isFalse();
        }
    }
}
