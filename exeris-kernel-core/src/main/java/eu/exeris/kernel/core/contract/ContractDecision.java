/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.contract;

import eu.exeris.kernel.spi.contract.ExecutionContract;
import eu.exeris.kernel.spi.contract.WorkloadAdmission;

import java.util.Objects;

/**
 * What the contract gate decides: the {@link ExecutionContract} the kernel runs under and the
 * {@link WorkloadAdmission} its drivers consult.
 *
 * <p>Both are bound together for the whole kernel scope, so a driver started inside the scope sees the
 * contract and the gate that belong to one decision.
 *
 * @param contract  the contract the kernel runs under
 * @param admission the workload gate; {@link WorkloadAdmission#UNGATED} when no developer envelope is in force
 * @since 0.13
 */
public value record ContractDecision(ExecutionContract contract, WorkloadAdmission admission) {

    /**
     * Compact constructor rejecting null components.
     */
    public ContractDecision {
        Objects.requireNonNull(contract, "contract must not be null");
        Objects.requireNonNull(admission, "admission must not be null");
    }
}
