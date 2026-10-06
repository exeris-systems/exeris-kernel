/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.contract.jfr;

import eu.exeris.kernel.spi.contract.ContractViolation;
import eu.exeris.kernel.spi.contract.EnforcementLevel;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reports a {@link ContractViolation} the way its enforcement level requires (ADR-089 §3).
 *
 * <ul>
 *   <li>{@link EnforcementLevel#SOFT}: a structured {@code WARNING} through {@link System.Logger} — on the
 *       default logging backend, standard error — and a {@link ContractEnforcementEvent}.</li>
 *   <li>{@link EnforcementLevel#AUDIT}: a {@link ContractEnforcementEvent} only, the local record a
 *       reconciliation reads; nothing is logged.</li>
 * </ul>
 *
 * <p>Neither level stops the caller. The event stays in the process's own recording: the kernel forwards it
 * nowhere (ADR-089 obligation 5), and it is recorded only while a JFR recording with the event enabled is
 * running — an {@code AUDIT} violation with no recording leaves no trace.
 *
 * <p>Each distinct violation — contract, constraint, level and subject — is reported once per process, so a
 * caller that checks the same capability repeatedly does not repeat the warning or the event. The set of
 * reported violations is bounded; past the bound, violations are reported every time rather than remembered.
 *
 * @since 0.13
 */
public final class ContractViolationReporter {

    private static final System.Logger LOGGER = System.getLogger(ContractViolationReporter.class.getName());
    private static final int MAX_REMEMBERED = 1024;
    private static final Set<ContractViolation> REPORTED = ConcurrentHashMap.newKeySet();

    private ContractViolationReporter() {}

    /**
     * Reports one violation.
     *
     * @param violation the violation to report
     */
    public static void report(ContractViolation violation) {
        Objects.requireNonNull(violation, "violation must not be null");
        if (!isFirstReport(violation)) {
            return;
        }
        String subject = printable(violation.subject());
        if (violation.level() != EnforcementLevel.AUDIT) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Execution contract {0}: {1} ({2}): {3}; the kernel keeps running",
                    printable(violation.contractId()), violation.constraintKey(), violation.level(), subject);
        }
        ContractEnforcementEvent.emit(
                violation.contractId(), violation.constraintKey(), violation.level().name(), subject);
    }

    /**
     * Records {@code violation} as reported. Past {@link #MAX_REMEMBERED} distinct violations nothing more
     * is remembered, and every later one counts as a first report.
     *
     * @return {@code false} when the same violation was already reported
     */
    private static boolean isFirstReport(ContractViolation violation) {
        if (REPORTED.contains(violation)) {
            return false;
        }
        return REPORTED.size() >= MAX_REMEMBERED || REPORTED.add(violation);
    }

    /**
     * Replaces control, format and line/paragraph separator characters, so a value from a manifest cannot
     * forge or reorder a log line. Returns {@code value} itself when it holds none.
     */
    private static String printable(String value) {
        int first = 0;
        while (first < value.length() && !isUnsafe(value.charAt(first))) {
            first++;
        }
        if (first == value.length()) {
            return value;
        }
        char[] clean = value.toCharArray();
        for (int i = first; i < clean.length; i++) {
            if (isUnsafe(clean[i])) {
                clean[i] = '?';
            }
        }
        return new String(clean);
    }

    private static boolean isUnsafe(char character) {
        int type = Character.getType(character);
        return type == Character.CONTROL
                || type == Character.FORMAT
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR;
    }
}
