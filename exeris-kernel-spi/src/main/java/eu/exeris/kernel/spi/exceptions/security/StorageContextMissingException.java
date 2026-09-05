/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.exceptions.security;

import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;

/**
 * Thrown when code attempts to read {@code KernelProviders.STORAGE_CONTEXT}
 * outside of a bound {@link java.lang.ScopedValue} scope.
 *
 * <h2>Error Code</h2>
 * <p>{@link KernelErrorCodes#EX_SEC_2004} — context missing.
 *
 * <h2>rawArgs layout (Glass-Box Telemetry)</h2>
 * <p>No raw args — this is a pure "missing context" signal.
 *
 * @since 0.5
 */
public final class StorageContextMissingException extends ExerisKernelException {

    /**
     * Creates a new storage-context-missing exception.
     */
    public StorageContextMissingException() {
        super(KernelErrorCodes.EX_SEC_2004, "StorageContext not bound — "
                + "code is executing outside of a ScopedValue security scope", (Throwable) null);
    }
}

