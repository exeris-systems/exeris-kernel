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
 * <p>Both tiers run the identical tests above through the {@code MemoryAllocator} and
 * {@link LoanedBuffer} SPI only; this class has no visibility into how a tier reclaims
 * memory internally. A pass establishes that the same observable contract — the segment
 * stays valid until the last reference closes, and every access after that throws rather
 * than crashing — holds for both:
 * <ul>
 *   <li><b>Community</b>: internally returns the segment via {@code Arena.close()}.</li>
 *   <li><b>Enterprise</b>: internally returns the slab to {@code PartitionedSlabPool} via a
 *       Treiber-stack CAS push instead of {@code Arena.close()}.</li>
 * </ul>
 * <p>Which mechanism runs, and whether that CAS is ABA-safe, is not observable from this
 * SPI-only TCK; it is the tier's own binding-test responsibility.
 *
 * @since 0.5
 */
public abstract class AbstractPanamaSegfaultShieldTck {

    /**
     * Subclass supplies the allocator under test.
     *
     * @return allocator; must not be {@code null}
     */
    protected abstract MemoryAllocator createAllocator();

    private MemoryAllocator allocator;

    /**
     * Creates the contract; subclasses supply the {@link MemoryAllocator} under test via
     * {@link #createAllocator()}.
     */
    public AbstractPanamaSegfaultShieldTck() {
        // Declared, not added: the implicit no-arg constructor, written out so it can carry a comment.
        super();
    }

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

        LoanedBuffer slice1 = parent.slice(0, 4 * 1024);
        LoanedBuffer slice2 = parent.slice(4 * 1024, 4 * 1024);
        LoanedBuffer slice3 = parent.slice(8 * 1024, 4 * 1024);

        parent.close();

        slice1.segment().set(ValueLayout.JAVA_INT, 0, 0xAAAA);
        slice2.segment().set(ValueLayout.JAVA_INT, 0, 0xBBBB);
        slice3.segment().set(ValueLayout.JAVA_INT, 0, 0xCCCC);

        assertThat(slice1.segment().get(ValueLayout.JAVA_INT, 0)).isEqualTo(0xAAAA);
        assertThat(slice2.segment().get(ValueLayout.JAVA_INT, 0)).isEqualTo(0xBBBB);
        assertThat(slice3.segment().get(ValueLayout.JAVA_INT, 0)).isEqualTo(0xCCCC);

        slice1.close();
        assertThat(slice2.isAlive()).isTrue();

        slice2.close();
        assertThat(slice3.isAlive()).isTrue();

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
        buf.retain();
        assertThat(buf.refCount()).isEqualTo(2);

        buf.close();
        assertThat(buf.isAlive()).isTrue();

        buf.close();
        assertThat(buf.isAlive()).isFalse();
    }

    @Test
    @DisplayName("Nested slices: grandchild holds grandparent alive")
    void nestedSlicesChainRefCounts() {
        LoanedBuffer parent = allocator.allocate(AllocationHint.MEDIUM);
        LoanedBuffer child = parent.slice(0, 4 * 1024);
        LoanedBuffer grand = child.slice(0, 1024);

        parent.close();
        assertThat(parent.isAlive()).isTrue();

        child.close();
        assertThat(grand.isAlive()).isTrue();

        grand.close();
        assertThat(grand.isAlive()).isFalse();
    }
}
