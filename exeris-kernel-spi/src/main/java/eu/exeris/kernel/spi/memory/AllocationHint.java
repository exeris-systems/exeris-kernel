/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.memory;

/**
 * Semantic "T-Shirt Sizing" hints for {@link MemoryAllocator} allocations.
 *
 * <p>Business logic MUST use these constants instead of raw byte literals.
 * The active allocator maps each hint to the optimal pool bucket for the
 * running tier (Community Heap-Arena vs Enterprise mmapped Slab).
 *
 * <h2>Valhalla-Readiness</h2>
 * <p>Sizes are exposed via a primitive accessor ({@link #sizeBytes()}) to allow
 * inlining by the JIT. Enum constants are JVM-singletons so no object churn occurs.
 *
 * <h2>Sizing Contract</h2>
 * <table>
 *   <tr><th>Hint</th><th>Size</th><th>Typical use</th></tr>
 *   <tr><td>MICRO</td><td>512 B</td><td>Error codes, ACK frames, ping/pong</td></tr>
 *   <tr><td>SMALL</td><td>4 KB</td><td>Single entity, health-check, short RPC reply</td></tr>
 *   <tr><td>MEDIUM</td><td>16 KB</td><td>Paginated list, standard REST response</td></tr>
 *   <tr><td>STREAMING_CHUNK</td><td>32 KB</td><td>Streaming data chunks (graph, file)</td></tr>
 *   <tr><td>JUMBO</td><td>128 KB</td><td>Bulk import/export, large binary blob</td></tr>
 *   <tr><td>NETWORK_FRAME</td><td>64 KB</td><td>Maximum IP datagram / UDP payload size</td></tr>
 * </table>
 *
 * @see MemoryAllocator#allocate(AllocationHint)
 * @since 0.5.0
 */
public enum AllocationHint {

    /**
     * Micro: 512 bytes.
     * <p>Error-code frames, heartbeat ACKs, short control messages.
     * Always heap TLAB; no pool lock required.
     */
    MICRO(512),

    /**
     * Small: 4 096 bytes.
     * <p>Single entity responses, health-check payloads, short RPC replies.
     * Always heap TLAB (~50 ns allocation path).
     */
    SMALL(4 * 1_024),

    /**
     * Medium: 16 384 bytes.
     * <p>Paginated list responses, standard REST API payloads.
     * Heap TLAB below the configured off-heap threshold.
     */
    MEDIUM(16 * 1_024),

    /**
     * Network frame: 64 KB (65 536 bytes).
     * <p>Maximum IP datagram / UDP payload size for transport pipelines.
     * Typically off-heap to avoid GC pauses on high-throughput paths.
     */
    NETWORK_FRAME(64 * 1_024),

    /**
     * Streaming chunk: 32 768 bytes.
     * <p>Reusable chunk size for streaming data (graph traversal, file upload/download).
     * The <em>chunk</em> size, not the total response size.
     */
    STREAMING_CHUNK(32 * 1_024),

    /**
     * Jumbo: 131 072 bytes.
     * <p>Bulk data import/export, large binary blobs.
     * Always off-heap; sourced from the GlobalArbiter slab pool in Enterprise tier.
     */
    JUMBO(128 * 1_024);

    private final int sizeBytes;

    AllocationHint(int sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    /**
     * Returns the canonical buffer size in bytes for this hint.
     *
     * <p>This is a {@code int} to allow the JIT to inline it as a compile-time constant
     * at call sites. Do NOT treat this as an upper bound; the allocator may return a
     * buffer with {@code capacity() > sizeBytes()} due to alignment/pool rounding.
     *
     * @return canonical size in bytes
     */
    public int sizeBytes() {
        return sizeBytes;
    }
}
