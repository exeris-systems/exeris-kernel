/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.memory;

import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;

/**
 * Thrown by the {@code MemoryAllocator} SPI when a byte-allocation request cannot be satisfied
 * because the off-heap tier has reached capacity.
 *
 * <h2>Zero-Allocation Contract (The Wall)</h2>
 * <p>This constructor performs <strong>zero String formatting</strong>. The numeric context
 * ({@code requestedBytes}, {@code availableBytes}) is stored in {@link #rawArgs()} at
 * indices {@code [0]} and {@code [1]} respectively, ready for binary Black-Box serialization.
 *
 * <h2>rawArgs Binary Layout (Enterprise Black-Box)</h2>
 * <pre>
 * index 0 → long  requestedBytes   (bytes the allocator was asked for)
 * index 1 → long  availableBytes   (bytes actually remaining in the tier)
 * </pre>
 *
 * <h2>Error Code</h2>
 * <p>{@value KernelErrorCodes#EX_MEM_1001}
 *
 * <h2>Legacy Migration Note</h2>
 * <p>The legacy {@code kernel-legacy/spi} version used:
 * <pre>{@code
 * // ❌ BANNED – allocates a StringBuilder + String on every throw:
 * super("Memory exhausted: requested %d bytes, available %d bytes"
 *       .formatted(requestedBytes, availableBytes));
 * }</pre>
 * <p>The new version passes raw {@code long} values:
 * <pre>{@code
 * // ✅ CORRECT – zero String allocation:
 * super(KernelErrorCodes.EX_MEM_1001, "Off-heap allocator exhausted", null,
 *       requestedBytes, availableBytes);
 * }</pre>
 *
 * @since 0.5.0
 */
public final class MemoryExhaustedException extends ExerisKernelException {

    /**
     * Static message template – compiled to a JVM string constant; never re-allocated.
     * The numeric context lives in {@code rawArgs}, not here.
     */
    private static final String MESSAGE = "Off-heap allocator exhausted";

    /**
     * Primary constructor – called by the {@code MemoryAllocator} SPI on allocation failure.
     *
     * <p>Both {@code requestedBytes} and {@code availableBytes} are stored as raw
     * {@code long} primitives (auto-boxed to {@code Long} by the varargs mechanism –
     * a single, short-lived allocation per throw, which is acceptable because an
     * {@code OutOfMemory}-class event is not a steady-state hot-path).
     *
     * @param requestedBytes number of bytes the caller requested; must be {@code > 0}
     * @param availableBytes number of bytes remaining in the allocator tier at the time of failure
     */
    public MemoryExhaustedException(long requestedBytes, long availableBytes) {
        super(KernelErrorCodes.EX_MEM_1001, MESSAGE, null, requestedBytes, availableBytes);
    }

    /**
     * Chained constructor – wraps a lower-level {@link Throwable} (e.g. an FFM
     * {@code IllegalStateException} from the arena).
     *
     * @param requestedBytes number of bytes the caller requested
     * @param availableBytes number of bytes remaining in the allocator tier
     * @param cause          the upstream throwable that triggered the exhaustion
     */
    public MemoryExhaustedException(long requestedBytes, long availableBytes, Throwable cause) {
        super(KernelErrorCodes.EX_MEM_1001, MESSAGE, cause, requestedBytes, availableBytes);
    }

    // -----------------------------------------------------------------------
    // Typed accessors – read rawArgs with explicit semantics
    // (Community telemetry may call these; Enterprise reads rawArgs() directly)
    // -----------------------------------------------------------------------

    /**
     * Returns the number of bytes that were requested at the time of failure.
     *
     * <p>This is a convenience accessor over {@code ((Number) rawArgs()[0]).longValue()}
     * with an explicit semantic name for Community-tier logging paths.
     *
     * @return requested byte count; always {@code > 0}
     */
    public long requestedBytes() {
        return ((Number) rawArgs()[0]).longValue();
    }

    /**
     * Returns the number of bytes that were available in the allocator tier
     * at the time of failure.
     *
     * <p>This is a convenience accessor over {@code ((Number) rawArgs()[1]).longValue()}.
     *
     * @return available byte count; may be {@code 0}
     */
    public long availableBytes() {
        return ((Number) rawArgs()[1]).longValue();
    }
}



