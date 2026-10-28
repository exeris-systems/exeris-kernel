/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.contract;

import java.util.Objects;

/**
 * A constraint of the active {@link ExecutionContract} that was not met at a level below
 * {@link EnforcementLevel#HARD}: the kernel keeps running and the violation is reported instead.
 *
 * <p>{@link EnforcementLevel#SOFT} is reported as a structured warning and a JFR event;
 * {@link EnforcementLevel#AUDIT} is recorded as a JFR event only (ADR-089 §3).
 *
 * @param contractId    contract the violation is measured against
 * @param constraintKey constraint that was not met, e.g. {@code "capability"} or {@code "environment"}
 * @param level         enforcement level the contract assigns that constraint
 * @param subject       what was refused, e.g. the capability or environment identifier
 * @since 0.13
 */
public record ContractViolation(
        String contractId,
        String constraintKey,
        EnforcementLevel level,
        String subject
) {

    /**
     * Compact constructor rejecting null components.
     */
    public ContractViolation {
        Objects.requireNonNull(contractId, "contractId must not be null");
        Objects.requireNonNull(constraintKey, "constraintKey must not be null");
        Objects.requireNonNull(level, "level must not be null");
        Objects.requireNonNull(subject, "subject must not be null");
    }
}
