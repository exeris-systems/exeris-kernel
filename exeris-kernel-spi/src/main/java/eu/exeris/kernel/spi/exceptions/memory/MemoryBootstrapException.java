/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
        super(KernelErrorCodes.EX_BOOT_0004, MESSAGE, cause, providerName, requestedBytes);
    }

    /**
     * Convenience constructor when no byte count is known.
     *
     * @param providerName name of the provider that failed
     * @param cause        root cause
     */
    public MemoryBootstrapException(String providerName, Throwable cause) {
        super(KernelErrorCodes.EX_BOOT_0004, MESSAGE, cause, providerName, -1L);
    }

    /**
     * Convenience constructor for cases with a descriptive message only.
     *
     * @param message static description (no runtime formatting)
     */
    public MemoryBootstrapException(String message) {
        super(KernelErrorCodes.EX_BOOT_0004, message);
    }
}


