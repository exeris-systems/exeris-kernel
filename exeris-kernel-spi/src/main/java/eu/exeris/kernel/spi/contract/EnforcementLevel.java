/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.contract;

/**
 * Three-tier enforcement level governing capability entitlement and execution contract constraints.
 *
 * <p>Enforcement actions are categorized into three orthogonal levels per ADR-089:
 * <ul>
 *   <li>{@link #HARD}: Violations throw {@code ContractBreachException} and fail-fast.</li>
 *   <li>{@link #SOFT}: Violations log structured warnings and emit JFR events, remaining operational.</li>
 *   <li>{@link #AUDIT}: Violations record telemetry for reconciliation without dropping requests.</li>
 * </ul>
 *
 * @since 0.13
 * @see ExecutionContract
 */
public enum EnforcementLevel {
    /** A violation throws {@code ContractBreachException}; the kernel does not start or continue. */
    HARD,
    /** A violation is reported as a structured warning and a JFR event; the kernel keeps running. */
    SOFT,
    /** A violation is recorded for reconciliation only; no request is refused or dropped. */
    AUDIT
}
