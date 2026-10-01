/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.exceptions.memory;

import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.memory.MemoryProvider;

/**
 * Thrown by {@link MemoryProvider#createAllocator} when the underlying
 * off-heap region cannot be initialised (e.g., insufficient system memory,
 * missing native library, or mmap permission denied).
 *
 * <h2>rawArgs Binary Layout (Enterprise Glass-Box)</h2>
 * <pre>
 * index 0 → String  providerName   (e.g. "ExerisEnterprise/GlobalArbiter")
 * index 1 → long    requestedBytes (total off-heap budget requested; -1 if unknown)
 * </pre>
 *
 * <h2>Error Code</h2>
 * <p>{@value KernelErrorCodes#EX_BOOT_0004} — dedicated code for memory-provider
 * bootstrap failures; distinct from {@value KernelErrorCodes#EX_BOOT_0002} which
 * covers general subsystem lifecycle failures with a different rawArgs schema.
 *
 * @apiNote A memory tier is a precondition for every other subsystem, so there is nothing to
 *          fall back to: treat this as terminal for the bootstrap attempt rather than
 *          catching it and continuing.
 * @since 0.5
 */
public final class MemoryBootstrapException extends ExerisKernelException {

    private static final String MESSAGE = "Memory provider bootstrap failed";

    /**
     * Records a provider initialisation failure with the full rawArgs pair — the provider's
     * name and the budget it could not reserve — and no String formatting.
     *
     * @param providerName   name of the provider that failed
     * @param requestedBytes off-heap budget that could not be allocated
     * @param cause          root cause from the native/arena layer
     */
    public MemoryBootstrapException(String providerName, long requestedBytes, Throwable cause) {
        super(KernelErrorCodes.EX_BOOT_0004, MESSAGE, cause, providerName, requestedBytes);
    }

    /**
     * Records a provider initialisation failure that happened before any budget was
     * determined — a rejected configuration, a missing native library — writing {@code -1}
     * into the {@code requestedBytes} slot to mark it unknown.
     *
     * @param providerName name of the provider that failed
     * @param cause        root cause
     */
    public MemoryBootstrapException(String providerName, Throwable cause) {
        super(KernelErrorCodes.EX_BOOT_0004, MESSAGE, cause, providerName, -1L);
    }

    /**
     * Records a failure that has no provider to name — no binding was found at all — using
     * the given static description as the message.
     *
     * @param message static description (no runtime formatting)
     * @apiNote This form leaves {@link #rawArgs()} empty rather than filling the layout above
     *          with placeholders, so a Glass-Box consumer must handle a zero-length array
     *          for {@code EX-BOOT-0004}. Prefer a constructor that names the provider
     *          whenever one is known.
     */
    public MemoryBootstrapException(String message) {
        super(KernelErrorCodes.EX_BOOT_0004, message);
    }
}


