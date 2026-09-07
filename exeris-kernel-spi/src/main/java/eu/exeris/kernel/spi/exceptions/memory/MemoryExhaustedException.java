/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.exceptions.memory;

import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;

/**
 * Thrown by the {@code MemoryAllocator} SPI when a byte-allocation request cannot be satisfied
 * because the off-heap tier has reached capacity.
 *
 * <h2>Zero-Allocation Contract (The Wall)</h2>
 * <p>Construction performs <strong>zero String formatting</strong>: the message is a
 * constant, and the numeric context ({@code requestedBytes}, {@code availableBytes}) is
 * carried in {@link #rawArgs()} at indices {@code [0]} and {@code [1]}, ready for binary
 * Glass-Box serialization. Formatting, if any is wanted, happens at the reporting end.
 *
 * <h2>rawArgs Binary Layout (Enterprise Glass-Box)</h2>
 * <pre>
 * index 0 → long  requestedBytes   (bytes the allocator was asked for)
 * index 1 → long  availableBytes   (bytes actually remaining in the tier)
 * </pre>
 *
 * <h2>Error Code</h2>
 * <p>{@value KernelErrorCodes#EX_MEM_1001}
 *
 * @apiNote Catching this is a load-shedding decision, not a retry: the tier is full, and the
 *          allocator will not grow it. The documented response is backpressure —
 *          {@code H3_EXCESSIVE_LOAD} — rather than an immediate second attempt.
 * @since 0.5
 */
public final class MemoryExhaustedException extends ExerisKernelException {

    /**
     * Static message template – compiled to a JVM string constant; never re-allocated.
     * The numeric context lives in {@code rawArgs}, not here.
     */
    private static final String MESSAGE = "Off-heap allocator exhausted";

    /**
     * Records an allocation failure with the two numbers that explain it, for a
     * {@code MemoryAllocator} implementation that has run out of tier capacity.
     *
     * @param requestedBytes number of bytes the caller requested; must be {@code > 0}
     * @param availableBytes number of bytes remaining in the allocator tier at the time of failure
     * @implNote The two values reach {@link #rawArgs()} through a varargs array, so each
     *           throw boxes them — one short-lived allocation on a path that is by
     *           definition not steady state.
     */
    public MemoryExhaustedException(long requestedBytes, long availableBytes) {
        super(KernelErrorCodes.EX_MEM_1001, MESSAGE, null, requestedBytes, availableBytes);
    }

    /**
     * Records an allocation failure that was surfaced by a lower-level throwable — an
     * {@code OutOfMemoryError} from the JVM or an {@code IllegalStateException} from an FFM
     * arena — keeping that original as the cause.
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
     * Returns the byte count the allocator could not satisfy — {@code rawArgs()[0]}, read
     * back with its meaning attached.
     *
     * <p>Community-tier logging paths use this; Enterprise Glass-Box reads
     * {@link #rawArgs()} directly.
     *
     * @return requested byte count; always {@code > 0}
     */
    public long requestedBytes() {
        return ((Number) rawArgs()[0]).longValue();
    }

    /**
     * Returns how much room the tier had left when the request was refused — {@code
     * rawArgs()[1]}, read back with its meaning attached.
     *
     * @return available byte count; may be {@code 0}
     */
    public long availableBytes() {
        return ((Number) rawArgs()[1]).longValue();
    }
}



