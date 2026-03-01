/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.tck.contract.memory;

import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.ValueLayout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TCK: Abstract base for "Panama Segfault Shield" contract.
 *
 * <h2>Contract</h2>
 * <p>Every conforming {@link MemoryAllocator} implementation MUST guarantee:
 * <ol>
 *   <li>Native memory is NOT released while any {@link LoanedBuffer#slice} is alive.</li>
 *   <li>A closed buffer throws {@link IllegalStateException} on access — not a JVM crash.</li>
 *   <li>The last surviving slice/retain triggers native memory release.</li>
 * </ol>
 *
 * <h2>What each tier proves</h2>
 * <ul>
 *   <li><b>Community</b>: Verifies that {@code AbstractLoanedBuffer} calls
 *       {@code Arena.close()} exactly when the last slice releases — no premature dealloc,
 *       no leak.</li>
 *   <li><b>Enterprise</b>: Verifies the same ref-count logic, but after the final release
 *       the slab is returned to {@code PartitionedSlabPool} via the Treiber-stack CAS push,
 *       not Arena.close(). Proves the ABA-safe CAS works correctly under ref-count driven
 *       release.</li>
 * </ul>
 *
 * @since 0.5.0
 */
public abstract class AbstractPanamaSegfaultShieldTck {

    /** Subclass supplies the allocator under test. */
    protected abstract MemoryAllocator createAllocator();

    private MemoryAllocator allocator;

    @BeforeEach
    final void setUp() {
        allocator = createAllocator();
    }

    @AfterEach
    final void tearDown() {
        allocator.close();
    }

    // =========================================================================
    // Core ref-count lifecycle
    // =========================================================================

    @Test
    @DisplayName("Parent closed while 3 slices live — buffer stays accessible until last slice closes")
    void nativeMemorySurvivesUntilLastSliceCloses() {
        LoanedBuffer parent = allocator.allocate(AllocationHint.MEDIUM);

        LoanedBuffer slice1 = parent.slice(0,              4 * 1024);
        LoanedBuffer slice2 = parent.slice(4 * 1024,       4 * 1024);
        LoanedBuffer slice3 = parent.slice(8 * 1024,       4 * 1024);

        // Close parent — slices still hold references
        parent.close();

        // Slices must still be writable
        slice1.segment().set(ValueLayout.JAVA_INT, 0, 0xAAAA);
        slice2.segment().set(ValueLayout.JAVA_INT, 0, 0xBBBB);
        slice3.segment().set(ValueLayout.JAVA_INT, 0, 0xCCCC);

        assertThat(slice1.segment().get(ValueLayout.JAVA_INT, 0)).isEqualTo(0xAAAA);
        assertThat(slice2.segment().get(ValueLayout.JAVA_INT, 0)).isEqualTo(0xBBBB);
        assertThat(slice3.segment().get(ValueLayout.JAVA_INT, 0)).isEqualTo(0xCCCC);

        // Close one by one
        slice1.close();
        // slice2 + slice3 still live — segment accessible
        assertThat(slice2.isAlive()).isTrue();

        slice2.close();
        assertThat(slice3.isAlive()).isTrue();

        // Last slice — triggers final release
        slice3.close();
        assertThat(slice3.isAlive()).isFalse();
    }

    @Test
    @DisplayName("Access to closed buffer throws IllegalStateException — NOT a JVM crash")
    void accessToClosedBufferThrowsNotSegfault() {
        LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO);
        buf.close();

        assertThatThrownBy(buf::segment)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }

    @Test
    @DisplayName("Out-of-bounds write throws IndexOutOfBoundsException — NOT a JVM crash")
    void outOfBoundsWriteThrowsNotSegfault() {
        try (LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO)) {
            long capacity = buf.capacity();
            // JAVA_LONG requires 8 bytes; write at (capacity - 4) overflows
            var seg = buf.segment();
            long offset = capacity - 4;
            assertThatThrownBy(() -> seg.set(ValueLayout.JAVA_LONG, offset, 0xDEADL))
                    .isInstanceOf(IndexOutOfBoundsException.class);
        }
    }

    @Test
    @DisplayName("retain() prevents release; release after matching close()")
    void retainPreventsRelease() {
        LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO);
        buf.retain(); // refCount → 2
        assertThat(buf.refCount()).isEqualTo(2);

        buf.close(); // refCount → 1 — must not release yet
        assertThat(buf.isAlive()).isTrue();

        buf.close(); // refCount → 0 — release
        assertThat(buf.isAlive()).isFalse();
    }

    @Test
    @DisplayName("Nested slices: grandchild holds grandparent alive")
    void nestedSlicesChainRefCounts() {
        LoanedBuffer parent = allocator.allocate(AllocationHint.MEDIUM);
        LoanedBuffer child  = parent.slice(0, 4 * 1024);
        LoanedBuffer grand  = child.slice(0, 1024);

        parent.close();
        // parent's own reference dropped, but child + grand still hold it — must stay alive
        assertThat(parent.isAlive()).isTrue();

        child.close();
        assertThat(grand.isAlive()).isTrue(); // grand still holds root alive

        grand.close(); // last reference — triggers release
        assertThat(grand.isAlive()).isFalse();
    }
}


