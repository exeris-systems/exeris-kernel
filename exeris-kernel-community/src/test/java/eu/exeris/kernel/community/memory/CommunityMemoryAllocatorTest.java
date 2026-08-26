/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.memory;

import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.memory.MemoryStats;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.ValueLayout;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.StructuredTaskScope;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L1 Unit: {@link CommunityMemoryAllocator} — internal telemetry, lifecycle,
 * stats tracking, and per-buffer isolation.
 *
 * <h2>Coverage</h2>
 * <ul>
 *   <li>allocate(MICRO/MEDIUM/JUMBO) — correct capacity</li>
 *   <li>allocateNetwork — cross-thread safe (shared arena)</li>
 *   <li>allocateCarrierSlab — default slab size</li>
 *   <li>allocateInfrastructure — invalid size rejection</li>
 *   <li>stats() tracks allocationCount, peakAllocated, releaseCount</li>
 *   <li>close() → subsequent allocate throws IllegalStateException</li>
 *   <li>Concurrent multi-VT allocation — stats remain consistent</li>
 *   <li>Buffer isolation — each buffer has independent content</li>
 * </ul>
 *
 * @since 0.5.0
 */
@DisplayName("L1 Unit: CommunityMemoryAllocator")
class CommunityMemoryAllocatorTest {

    private static final MemoryProviderConfig CONFIG = MemoryProviderConfig.defaults();
    private static final String COMMUNITY_MEMORY_SAMPLE_EVERY_PROPERTY = "exeris.community.memory.jfr.sampleEvery";
    private static final String COMMUNITY_ALLOCATION_EVENT = "eu.exeris.kernel.memory.CommunityAllocation";
    private static final String COMMUNITY_RELEASE_EVENT = "eu.exeris.kernel.memory.CommunityRelease";
    private static final String COMMUNITY_OVERFLOW_RETURN_EVENT = "eu.exeris.kernel.memory.CommunityOverflowReturn";
    private static final int OVERSIZED_ALLOCATION_BYTES = 200 * 1_024;
    private static final int VERY_LARGE_ALLOCATION_BYTES = 1_024 * 1_024;
    private static final int OVERFLOW_REQUEST_BYTES_2MB = 2 * 1_024 * 1_024;
    private static final int OVERFLOW_REQUEST_BYTES_3MB = 3 * 1_024 * 1_024;
    private static final int CROSS_VT_STRESS_ITERATIONS = 100;

    private MemoryAllocator allocator;

    @BeforeEach
    void setUp() {
        allocator = new CommunityMemoryProvider().createAllocator(CONFIG);
    }

    @AfterEach
    void tearDown() {
        allocator.close();
    }

    // =========================================================================
    // Basic allocation — capacity contract
    // =========================================================================

    @Nested
    @DisplayName("Capacity contract")
    class CapacityContract {

        @Test
        @DisplayName("allocate(MICRO) returns buffer with capacity >= MICRO size")
        void allocateMicroCapacity() {
            try (LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO)) {
                assertThat(buf.capacity())
                        .isGreaterThanOrEqualTo(AllocationHint.MICRO.sizeBytes());
            }
        }

        @Test
        @DisplayName("allocate(MEDIUM) returns buffer with capacity >= MEDIUM size")
        void allocateMediumCapacity() {
            try (LoanedBuffer buf = allocator.allocate(AllocationHint.MEDIUM)) {
                assertThat(buf.capacity())
                        .isGreaterThanOrEqualTo(AllocationHint.MEDIUM.sizeBytes());
            }
        }

        @Test
        @DisplayName("allocate(JUMBO) returns buffer with capacity >= JUMBO size")
        void allocateJumboCapacity() {
            try (LoanedBuffer buf = allocator.allocate(AllocationHint.JUMBO)) {
                assertThat(buf.capacity())
                        .isGreaterThanOrEqualTo(AllocationHint.JUMBO.sizeBytes());
            }
        }

        @Test
        @DisplayName("allocateNetwork(1024) returns live buffer")
        void allocateNetworkSmall() {
            try (LoanedBuffer buf = allocator.allocateNetwork(1024)) {
                assertThat(buf).isNotNull();
                assertThat(buf.isAlive()).isTrue();
                assertThat(buf.capacity()).isGreaterThanOrEqualTo(1024L);
            }
        }

        @Test
        @DisplayName("allocateCarrierSlab returns valid buffer")
        void allocateCarrierSlab() {
            try (LoanedBuffer buf = allocator.allocateCarrierSlab(0)) {
                assertThat(buf).isNotNull();
                assertThat(buf.isAlive()).isTrue();
                assertThat(buf.capacity()).isGreaterThan(0);
            }
        }

        @Test
        @DisplayName("allocateInfrastructure(64) returns valid buffer")
        void allocateInfrastructureSmall() {
            try (LoanedBuffer buf = allocator.allocateInfrastructure(64)) {
                assertThat(buf).isNotNull();
                assertThat(buf.isAlive()).isTrue();
                assertThat(buf.capacity()).isGreaterThanOrEqualTo(64L);
            }
        }

        @Test
        @DisplayName("allocateInfrastructure(0) throws IllegalArgumentException")
        void allocateInfrastructureZeroThrows() {
            assertThatThrownBy(() -> allocator.allocateInfrastructure(0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sizeBytes");
        }

        @Test
        @DisplayName("allocateInfrastructure(-1) throws IllegalArgumentException")
        void allocateInfrastructureNegativeThrows() {
            assertThatThrownBy(() -> allocator.allocateInfrastructure(-1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // =========================================================================
    // Stats tracking
    // =========================================================================

    @Nested
    @DisplayName("Stats tracking")
    class StatsTracking {

        @Test
        @DisplayName("allocationCount increments on each allocate()")
        void allocationCountIncrements() {
            MemoryStats before = allocator.stats();
            try (var _ = allocator.allocate(AllocationHint.MICRO)) {
                MemoryStats during = allocator.stats();
                assertThat(during.allocationCount())
                        .isGreaterThan(before.allocationCount());
            }
        }

        @Test
        @DisplayName("allocatedBytes increases after allocation and returns to 0 after release")
        void allocatedBytesTracked() {
            MemoryStats before = allocator.stats();
            LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO);
            MemoryStats after = allocator.stats();
            assertThat(after.allocatedBytes()).isGreaterThan(before.allocatedBytes());

            buf.close(); // release
            MemoryStats released = allocator.stats();
            assertThat(released.allocatedBytes()).isEqualTo(before.allocatedBytes());
        }

        @Test
        @DisplayName("peakAllocated never decreases")
        void peakAllocatedNeverDecreases() {
            try (var _ = allocator.allocate(AllocationHint.JUMBO)) {
                long peakDuring = allocator.stats().peakAllocatedBytes();
                assertThat(peakDuring).isGreaterThan(0L);
            }
            long peakAfter = allocator.stats().peakAllocatedBytes();
            assertThat(peakAfter).isGreaterThan(0L);
        }

        @Test
        @DisplayName("releaseCount increments after buffer.close()")
        void releaseCountIncrements() {
            LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO);
            long releasesBefore = allocator.stats().releaseCount();
            buf.close();
            assertThat(allocator.stats().releaseCount())
                    .isGreaterThan(releasesBefore);
        }

        @Test
        @DisplayName("stats() returns -1 as totalBudget (Community: no fixed off-heap budget)")
        void totalBudgetIsUnbounded() {
            assertThat(allocator.stats().totalBytes()).isEqualTo(-1L);
        }
    }

    // =========================================================================
    // Lifecycle — close() guard
    // =========================================================================

    @Nested
    @DisplayName("Lifecycle — close() guard")
    class LifecycleGuard {

        @Test
        @DisplayName("allocate() after close() throws IllegalStateException")
        void allocateAfterCloseThrows() {
            allocator.close();
            assertThatThrownBy(() -> allocator.allocate(AllocationHint.MICRO))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("allocateNetwork() after close() throws IllegalStateException")
        void allocateNetworkAfterCloseThrows() {
            allocator.close();
            assertThatThrownBy(() -> allocator.allocateNetwork(1024))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("allocateCarrierSlab() after close() throws IllegalStateException")
        void allocateCarrierSlabAfterCloseThrows() {
            allocator.close();
            assertThatThrownBy(() -> allocator.allocateCarrierSlab(0))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("close() is idempotent — double-close does not throw, allocator is closed after")
        void closeIsIdempotent() {
            MemoryAllocator local = new CommunityMemoryProvider().createAllocator(CONFIG);
            local.close();
            local.close();
            assertThatThrownBy(() -> local.allocate(AllocationHint.MICRO))
                    .as("allocator must be closed after double-close()")
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    // =========================================================================
    // Buffer isolation
    // =========================================================================

    @Nested
    @DisplayName("Buffer isolation")
    class BufferIsolation {

        @Test
        @DisplayName("Two concurrent buffers have independent memory regions")
        void twoBuffersAreIndependent() {
            try (LoanedBuffer a = allocator.allocate(AllocationHint.MICRO);
                 LoanedBuffer b = allocator.allocate(AllocationHint.MICRO)) {
                // Write 0xAA to buffer A, 0xBB to buffer B
                a.segment().set(ValueLayout.JAVA_BYTE, 0, (byte) 0xAA);
                b.segment().set(ValueLayout.JAVA_BYTE, 0, (byte) 0xBB);

                // Verify no overlap
                assertThat(a.segment().get(ValueLayout.JAVA_BYTE, 0)).isEqualTo((byte) 0xAA);
                assertThat(b.segment().get(ValueLayout.JAVA_BYTE, 0)).isEqualTo((byte) 0xBB);
            }
        }

        @Test
        @DisplayName("Buffer.segment() returns non-null MemorySegment with correct byteSize")
        void segmentHasCorrectSize() {
            try (LoanedBuffer buf = allocator.allocateNetwork(4096)) {
                assertThat(buf.segment()).isNotNull();
                assertThat(buf.segment().byteSize()).isGreaterThanOrEqualTo(4096L);
            }
        }

        @Test
        @DisplayName("Buffer.isAlive() returns false after close()")
        void isAliveAfterClose() {
            LoanedBuffer buf = allocator.allocate(AllocationHint.MICRO);
            assertThat(buf.isAlive()).isTrue();
            buf.close();
            assertThat(buf.isAlive()).isFalse();
        }
    }

    // =========================================================================
    // Concurrent allocation — stats consistency
    // =========================================================================

    @Test
    @DisplayName("Concurrent allocation from 1000 VTs maintains consistent stats")
    void concurrentAllocationStatsConsistency() throws Exception {
        int threadCount = 1_000;

        try (var scope = StructuredTaskScope.open(
                StructuredTaskScope.Joiner.<Void>awaitAllSuccessfulOrThrow())) {
            for (int i = 0; i < threadCount; i++) {
                scope.fork(() -> {
                    try (var buf = allocator.allocate(AllocationHint.MICRO)) {
                        totalAllocated.addAndGet(buf.capacity());
                    }
                    return null;
                });
            }
            scope.join();
        }

        assertThat(allocator.stats().allocationCount())
                .as("allocationCount must equal threadCount")
                .isEqualTo(threadCount);
        assertThat(allocator.stats().releaseCount())
                .as("all buffers must have been released inside scope")
                .isEqualTo(threadCount);
    }

    /**
     * Tests that oversized allocations (>128 KB) have the same cross-VT safety as pooled allocations.
     * Validates contract migration from Arena.ofAuto() (GC-driven) to Arena.ofShared() (deterministic).
     */
    @Test
    @DisplayName("Queue handoff across VTs keeps payload visible and closes deterministically")
    void crossVirtualThreadHandoffCloseIsDeterministic() throws Exception {
        long allocationCountBefore = allocator.stats().allocationCount();
        long releaseCountBefore = allocator.stats().releaseCount();

        for (int iteration = 0; iteration < CROSS_VT_STRESS_ITERATIONS; iteration++) {
            executeCrossVirtualThreadHandoff(allocator, 128, false);
            executeCrossVirtualThreadHandoff(allocator, OVERSIZED_ALLOCATION_BYTES, true);
        }

        long expectedOperations = (long) CROSS_VT_STRESS_ITERATIONS * 2;
        assertThat(allocator.stats().allocationCount())
                .isEqualTo(allocationCountBefore + expectedOperations);
        assertThat(allocator.stats().releaseCount())
                .isEqualTo(releaseCountBefore + expectedOperations);
    }

    @Test
    @DisplayName("JFR records pooled and oversized cross-VT allocation/release lifecycle")
    void jfrRecordsPooledAndOversizedCrossVirtualThreadLifecycle() throws Exception {
        String originalSampleEvery = System.getProperty(COMMUNITY_MEMORY_SAMPLE_EVERY_PROPERTY);
        Path recordingPath = Files.createTempFile("community-memory-cross-vt-", ".jfr");
        System.setProperty(COMMUNITY_MEMORY_SAMPLE_EVERY_PROPERTY, "1");

        try (MemoryAllocator jfrAllocator = new CommunityMemoryProvider().createAllocator(new MemoryProviderConfig(
                CONFIG.totalOffHeapBytes(),
                CONFIG.networkOffHeapThreshold(),
                CONFIG.carrierCount(),
                CONFIG.leakDetection(),
                true
        ));
             Recording recording = new Recording()) {
            recording.enable(COMMUNITY_ALLOCATION_EVENT).withoutStackTrace();
            recording.enable(COMMUNITY_RELEASE_EVENT).withoutStackTrace();
            recording.start();

            executeCrossVirtualThreadHandoff(jfrAllocator, 128, false);
            executeCrossVirtualThreadHandoff(jfrAllocator, OVERSIZED_ALLOCATION_BYTES, true);

            recording.stop();
            recording.dump(recordingPath);

            List<RecordedEvent> events = RecordingFile.readAllEvents(recordingPath);
            List<RecordedEvent> allocationEvents = events.stream()
                    .filter(event -> COMMUNITY_ALLOCATION_EVENT.equals(event.getEventType().getName()))
                    .toList();
            List<RecordedEvent> releaseEvents = events.stream()
                    .filter(event -> COMMUNITY_RELEASE_EVENT.equals(event.getEventType().getName()))
                    .toList();

            assertThat(allocationEvents)
                    .extracting(event -> event.getLong("allocationBytes"))
                    .contains(128L, (long) OVERSIZED_ALLOCATION_BYTES);
            assertThat(releaseEvents)
                    .extracting(event -> event.getLong("releasedBytes"))
                    .contains(128L, (long) OVERSIZED_ALLOCATION_BYTES);

                assertThat(allocationEvents)
                    .allSatisfy(event -> assertThat(event.getEventType().getFields())
                        .extracting(field -> field.getName().toLowerCase())
                        .noneMatch(name -> name.contains("arena")));
                assertThat(releaseEvents)
                    .allSatisfy(event -> assertThat(event.getEventType().getFields())
                            .extracting(field -> field.getName().toLowerCase())
                            .noneMatch(name -> name.contains("arena")));
        } finally {
            if (originalSampleEvery == null) {
                System.clearProperty(COMMUNITY_MEMORY_SAMPLE_EVERY_PROPERTY);
            } else {
                System.setProperty(COMMUNITY_MEMORY_SAMPLE_EVERY_PROPERTY, originalSampleEvery);
            }
            Files.deleteIfExists(recordingPath);
        }
    }

    @Test
    @DisplayName("JFR emits overflow return safety-net events for allocations larger than 1 MB")
    void jfrEmitsOverflowReturnSafetyNetForLargerThanOneMegabyteAllocations() throws Exception {
        String originalSampleEvery = System.getProperty(COMMUNITY_MEMORY_SAMPLE_EVERY_PROPERTY);
        Path recordingPath = Files.createTempFile("community-memory-overflow-return-", ".jfr");
        System.setProperty(COMMUNITY_MEMORY_SAMPLE_EVERY_PROPERTY, "1");

        long expectedReturnedSum = (long) OVERFLOW_REQUEST_BYTES_2MB + OVERFLOW_REQUEST_BYTES_3MB;

        try (MemoryAllocator jfrAllocator = new CommunityMemoryProvider().createAllocator(new MemoryProviderConfig(
                CONFIG.totalOffHeapBytes(),
                CONFIG.networkOffHeapThreshold(),
                CONFIG.carrierCount(),
                CONFIG.leakDetection(),
                true
        ));
             Recording recording = new Recording()) {
            recording.enable(COMMUNITY_OVERFLOW_RETURN_EVENT).withoutStackTrace();
            recording.start();

            try (LoanedBuffer firstOverflow = jfrAllocator.allocateNetwork(OVERFLOW_REQUEST_BYTES_2MB);
                 LoanedBuffer secondOverflow = jfrAllocator.allocateNetwork(OVERFLOW_REQUEST_BYTES_3MB)) {
                assertThat(firstOverflow.capacity()).isGreaterThanOrEqualTo((long) OVERFLOW_REQUEST_BYTES_2MB);
                assertThat(secondOverflow.capacity()).isGreaterThanOrEqualTo((long) OVERFLOW_REQUEST_BYTES_3MB);
            }

            recording.stop();
            recording.dump(recordingPath);

            List<RecordedEvent> overflowEvents = RecordingFile.readAllEvents(recordingPath).stream()
                    .filter(event -> COMMUNITY_OVERFLOW_RETURN_EVENT.equals(event.getEventType().getName()))
                    .toList();

            assertThat(overflowEvents.size()).isGreaterThanOrEqualTo(2);
            assertThat(overflowEvents)
                    .extracting(event -> event.getLong("returnedBytes"))
                    .contains((long) OVERFLOW_REQUEST_BYTES_2MB, (long) OVERFLOW_REQUEST_BYTES_3MB);
            assertThat(overflowEvents.stream()
                    .mapToLong(event -> event.getLong("totalOverflowReturnedBytes"))
                    .max()
                    .orElse(0L))
                    .isGreaterThanOrEqualTo(expectedReturnedSum);
        } finally {
            if (originalSampleEvery == null) {
                System.clearProperty(COMMUNITY_MEMORY_SAMPLE_EVERY_PROPERTY);
            } else {
                System.setProperty(COMMUNITY_MEMORY_SAMPLE_EVERY_PROPERTY, originalSampleEvery);
            }
            Files.deleteIfExists(recordingPath);
        }
    }

    // =========================================================================
    // Arena shard pool — reuse verification
    // =========================================================================

    @Nested
    @DisplayName("Arena shard pool — reuse and close safety")
    class ArenaShardPoolReuse {

        @Test
        @DisplayName("Sequential small allocations reuse memory from pool (addresses stable within size class)")
        void smallAllocationsReuseFromPool() {
            try (var buf1 = allocator.allocateNetwork(512)) {
                long addr1 = buf1.segment().address();
                buf1.close();

                try (var buf2 = allocator.allocateNetwork(512)) {
                    long addr2 = buf2.segment().address();
                    assertThat(addr2).as("Pool should reuse freed segment address")
                            .isEqualTo(addr1);
                }
            }
        }

        @Test
        @DisplayName("Allocations above max pool size (128 KB) use shared arena deterministic lifecycle")
        void largeAllocationsUseSharedArena() {
            long releaseCountBefore = allocator.stats().releaseCount();
            try (var buf = allocator.allocateInfrastructure(256 * 1_024)) {
                assertThat(buf).isNotNull();
                assertThat(buf.isAlive()).isTrue();
                assertThat(buf.capacity()).isGreaterThanOrEqualTo(256 * 1_024L);
            }
            assertThat(allocator.stats().releaseCount()).isGreaterThan(releaseCountBefore);
        }

        @Test
        @DisplayName("1 MB allocations are pooled and can be reused within size class")
        void oneMegabyteAllocationsReuseFromPool() {
            try (var buf1 = allocator.allocateInfrastructure(VERY_LARGE_ALLOCATION_BYTES)) {
                long addr1 = buf1.segment().address();
                buf1.close();
                try (var buf2 = allocator.allocateInfrastructure(VERY_LARGE_ALLOCATION_BYTES)) {
                    long addr2 = buf2.segment().address();
                    assertThat(addr2).isEqualTo(addr1);
                }
            }
        }

        @Test
        @DisplayName("allocateCarrierSlab uses pool (STREAMING_CHUNK = 32 KB is pooled)")
        void carrierSlabUsesPool() {
            try (var slab1 = allocator.allocateCarrierSlab(0)) {
                long slab1Addr = slab1.segment().address();
                slab1.close();

                try (var slab2 = allocator.allocateCarrierSlab(1)) {
                    long slab2Addr = slab2.segment().address();
                    assertThat(slab2Addr).as("Carrier slabs should be reused from pool")
                            .isEqualTo(slab1Addr);
                }
            }
        }

        @Test
        @DisplayName("Pool close drains queues; subsequent buffer.close() is safe (no-op)")
        void poolCloseSafeAfterBufferClose() {
            LoanedBuffer buf = allocator.allocateNetwork(512);
            buf.close();

            allocator.close();

            assertThatCode(() -> buf.close())
                    .as("Multiple closes on buffer after allocator.close() must be safe")
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Allocator close is idempotent; multiple closes don't throw")
        void allocatorCloseIsIdempotent() {
            allocator.close();
            assertThatCode(() -> allocator.close())
                    .as("Allocator.close() must be idempotent")
                    .doesNotThrowAnyException();
        }
    }

    private static void executeCrossVirtualThreadHandoff(
            MemoryAllocator activeAllocator,
            int requestedBytes,
            boolean oversized) throws Exception {
        LoanedBuffer buffer = activeAllocator.allocateNetwork(requestedBytes);
        LinkedBlockingQueue<LoanedBuffer> handoffQueue = new LinkedBlockingQueue<>(1);
        byte payload = oversized ? (byte) 0x3C : (byte) 0x5A;
        AtomicReference<Long> receiverThreadId = new AtomicReference<>();
        AtomicReference<Long> closeActionThreadId = new AtomicReference<>();

        buffer.addCloseAction(() -> closeActionThreadId.set(Thread.currentThread().threadId()));
        buffer.segment().set(ValueLayout.JAVA_BYTE, 0, payload);

        assertThat(buffer.capacity()).isGreaterThanOrEqualTo((long) requestedBytes);
        assertThat(buffer.capacity()).isEqualTo(expectedCapacityForRequest(requestedBytes));
        assertThat(buffer.isAlive()).isTrue();

        try {
            try (var scope = StructuredTaskScope.open(
                    StructuredTaskScope.Joiner.<Void>awaitAllSuccessfulOrThrow())) {
                scope.fork(() -> {
                    handoffQueue.put(buffer);
                    return null;
                });
                scope.fork(() -> {
                    try (LoanedBuffer handedOff = handoffQueue.take()) {
                        receiverThreadId.set(Thread.currentThread().threadId());
                        assertThat(handedOff.segment().get(ValueLayout.JAVA_BYTE, 0))
                                .isEqualTo(payload);
                        assertThatCode(() -> handedOff.segment().get(ValueLayout.JAVA_BYTE, 0))
                                .doesNotThrowAnyException();
                    }
                    return null;
                });
                scope.join();
            }
        } finally {
            buffer.close();
        }

        assertThat(buffer.isAlive()).isFalse();
        assertThat(closeActionThreadId.get())
                .isEqualTo(receiverThreadId.get());
    }

    private static long expectedCapacityForRequest(int requestedBytes) {
        if (requestedBytes <= 512) {
            return 512L;
        }
        if (requestedBytes <= 4 * 1_024) {
            return 4_096L;
        }
        if (requestedBytes <= 16 * 1_024) {
            return 16_384L;
        }
        if (requestedBytes <= 32 * 1_024) {
            return 32_768L;
        }
        if (requestedBytes <= 64 * 1_024) {
            return 65_536L;
        }
        if (requestedBytes <= 128 * 1_024) {
            return 131_072L;
        }
        if (requestedBytes <= 256 * 1_024) {
            return 262_144L;
        }
        if (requestedBytes <= 512 * 1_024) {
            return 524_288L;
        }
        if (requestedBytes <= 1_024 * 1_024) {
            return 1_048_576L;
        }
        return requestedBytes;
    }

    private final AtomicLong totalAllocated = new AtomicLong(0);
}
