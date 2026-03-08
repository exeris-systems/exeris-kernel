/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.exceptions.security;

import eu.exeris.kernel.spi.exceptions.ExerisKernelException;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;

/**
 * Thrown when code attempts to read {@code KernelProviders.PRINCIPAL_CONTEXT}
 * outside of a bound {@link java.lang.ScopedValue} scope.
 *
 * <h2>Error Code</h2>
 * <p>{@link KernelErrorCodes#EX_SEC_2001} — PrincipalContext missing.
 *
 * <h2>rawArgs layout (Glass-Box Telemetry)</h2>
 * <p>No raw args — this is a pure "missing context" signal.
 *
 * @since 0.5.0
 */
public final class PrincipalContextMissingException extends ExerisKernelException {

    /**
     * Creates a new principal-context-missing exception.
     */
    public PrincipalContextMissingException() {
        super(KernelErrorCodes.EX_SEC_2001, "PrincipalContext not bound — "
                + "code is executing outside of a ScopedValue security scope", (Throwable) null);
    }
}

