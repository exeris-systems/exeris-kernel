/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.exceptions.contract;

import eu.exeris.kernel.spi.exceptions.ExerisKernelException;

/**
 * Thrown when an execution contract constraint or license manifest verification rule is breached.
 *
 * <p>Covers cryptographic validation errors, missing production manifests, expired licenses,
 * and unentitled capability execution under HARD enforcement policy.
 *
 * @since 0.13
 * @see eu.exeris.kernel.spi.contract.ExecutionContract
 * @see eu.exeris.kernel.spi.exceptions.KernelErrorCodes
 */
public class ContractBreachException extends ExerisKernelException {

    /**
     * Constructs a new exception with a specific error code and message.
     *
     * @param errorCode canonical EX-LIC-* code
     * @param message   static description of the breach
     */
    public ContractBreachException(String errorCode, String message) {
        super(errorCode, message);
    }

    /**
     * Constructs a new exception with a specific error code, message, and cause.
     *
     * @param errorCode canonical EX-LIC-* code
     * @param message   static description of the breach
     * @param cause     root cause
     */
    public ContractBreachException(String errorCode, String message, Throwable cause) {
        super(errorCode, message, cause);
    }

    /**
     * Constructs a new exception with raw domain arguments for Glass-Box telemetry.
     *
     * @param errorCode canonical EX-LIC-* code
     * @param message   static description of the breach
     * @param rawArgs   domain arguments
     */
    public ContractBreachException(String errorCode, String message, Object... rawArgs) {
        super(errorCode, message, rawArgs);
    }

    /**
     * Constructs a new exception with cause and raw domain arguments for Glass-Box telemetry.
     *
     * @param errorCode canonical EX-LIC-* code
     * @param message   static description of the breach
     * @param cause     root cause
     * @param rawArgs   domain arguments
     */
    public ContractBreachException(String errorCode, String message, Throwable cause, Object... rawArgs) {
        super(errorCode, message, cause, rawArgs);
    }
}
