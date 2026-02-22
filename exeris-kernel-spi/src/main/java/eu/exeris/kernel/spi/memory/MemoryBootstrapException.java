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
 * Thrown by {@link MemoryProvider#createAllocator} when the underlying
 * off-heap region cannot be initialised (e.g., insufficient system memory,
 * missing native library, or mmap permission denied).
 *
 * <h2>rawArgs Binary Layout (Enterprise Black-Box)</h2>
 * <pre>
 * index 0 → String  providerName   (e.g. "ExerisEnterprise/GlobalArbiter")
 * index 1 → long    requestedBytes (total off-heap budget requested)
 * </pre>
 *
 * @since 0.5.0
 */
public final class MemoryBootstrapException extends ExerisKernelException {

    private static final String MESSAGE = "Memory provider bootstrap failed";

    /**
     * Primary constructor — zero String formatting.
     *
     * @param providerName   name of the provider that failed
     * @param requestedBytes off-heap budget that could not be allocated
     * @param cause          root cause from the native/arena layer
     */
    public MemoryBootstrapException(String providerName, long requestedBytes, Throwable cause) {
        super(KernelErrorCodes.EX_BOOT_0002, MESSAGE, cause, providerName, requestedBytes);
    }

    /**
     * Convenience constructor when no byte count is known.
     *
     * @param providerName name of the provider that failed
     * @param cause        root cause
     */
    public MemoryBootstrapException(String providerName, Throwable cause) {
        super(KernelErrorCodes.EX_BOOT_0002, MESSAGE, cause, providerName, -1L);
    }

    /**
     * Convenience constructor for cases with a descriptive message only.
     *
     * @param message static description (no runtime formatting)
     */
    public MemoryBootstrapException(String message) {
        super(KernelErrorCodes.EX_BOOT_0002, message, null, (Object[]) null);
    }
}


