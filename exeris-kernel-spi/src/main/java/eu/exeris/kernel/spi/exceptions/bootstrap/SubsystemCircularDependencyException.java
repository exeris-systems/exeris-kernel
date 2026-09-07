/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.exceptions.bootstrap;

import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Fatal L0 exception thrown when Kahn's topological sort algorithm detects a circular
 * dependency in the subsystem graph.
 *
 * <h2>L0 Hard Failure Semantics</h2>
 * <p>In the Exeris Kernel, a dependency cycle is an <em>unrecoverable architectural
 * defect</em> — not a runtime condition. There is no attempt to "heal" cycles;
 * the boot sequence halts immediately (via {@code SubsystemOrchestrator} in
 * {@code exeris-kernel-core}) and the JVM
 * exits. No degraded mode, no retry, no partial boot.
 *
 * <h2>Pre-allocation Pattern (Zero Heap on Hot Path)</h2>
 * <p>A single sentinel instance is pre-allocated at class-init time:
 * {@link #SENTINEL}. The orchestrator throws this exact instance — no new object
 * is created during the abort path. This eliminates object churn and GC pressure
 * at the moment the JVM is about to terminate anyway.
 *
 * <p>For diagnostic purposes (logging with cycle members), use
 * {@link #forCycle(Collection)} to build a descriptive instance. This is only
 * called once, at the point of detection, not on the throw site.
 *
 * <h2>Error Code</h2>
 * <p>{@link #ERROR_CODE} ({@value SubsystemCircularDependencyException#ERROR_CODE}) —
 * always treated as FAIL_FAST. Cannot be suppressed by {@code FailurePolicy.DEGRADE}.
 *
 * <h2>Example</h2>
 * {@snippet lang="java" :
 * // Kahn's topological sort in SubsystemOrchestrator:
 * if (result.size() != subsystems.size()) {
 *     Set<String> cycleMembers = new LinkedHashSet<>(byName.keySet());
 *     result.forEach(s -> cycleMembers.remove(s.name()));
 *     throw SubsystemCircularDependencyException.forCycle(cycleMembers);
 * }
 * }
 *
 * @since 0.5
 * @see eu.exeris.kernel.spi.exceptions.SubsystemException
 */
public final class SubsystemCircularDependencyException extends RuntimeException {

    /**
     * Telemetry error code for this failure class — {@link KernelErrorCodes#EX_BOOT_0001}.
     *
     * <p>Delegates to {@link KernelErrorCodes} — the single source of truth for all
     * {@code EX-*} codes — to prevent duplicated string literals across the kernel.
     *
     * <p>Always treated as {@code FAIL_FAST}. Cannot be suppressed by
     * {@code FailurePolicy.DEGRADE}.
     */
    public static final String ERROR_CODE = KernelErrorCodes.EX_BOOT_0001;

    /**
     * Pre-allocated sentinel instance — thrown when no diagnostic detail is available.
     *
     * <p>All {@code SubsystemCircularDependencyException} instances — including this sentinel —
     * are constructed with {@code enableSuppression=false, writableStackTrace=false} via
     * {@code super(message, null, false, false)}, so no stack trace is ever captured
     * and {@code addSuppressed()} is a no-op, keeping the sentinel immutable and
     * free of cross-throw state contamination.
     */
    public static final SubsystemCircularDependencyException SENTINEL =
            new SubsystemCircularDependencyException(
                    ERROR_CODE + ": Circular dependency detected in the subsystem graph. "
                    + "The Exeris Kernel cannot boot. "
                    + "Call SubsystemCircularDependencyException.forCycle(members) for detail.",
                    Set.of());

    // -------------------------------------------------------------------------

    /** Subsystem names forming the detected cycle, insertion-ordered; empty for {@link #SENTINEL}. */
    private final Set<String> cycleMembers;

    /**
     * Private constructor — use {@link #forCycle(Collection)} or {@link #SENTINEL}.
     */
    private SubsystemCircularDependencyException(String message, Set<String> cycleMembers) {
        // writableStackTrace=false: pre-allocated exceptions never capture stack trace
        // fillInStackTrace() intentionally NOT called — no heap allocation on abort path
        // enableSuppression=false: sentinel is a shared global instance; addSuppressed()
        // would acquire an internal lock and mutate state across throws, causing
        // cross-thread contamination and heap allocation on the abort path.
        super(message, null, false, false);
        this.cycleMembers = Collections.unmodifiableSet(cycleMembers);
    }

    /**
     * Creates a diagnostic instance naming every subsystem participating in the cycle.
     *
     * <p>This method allocates a new exception object — it is intended to be called
     * exactly once at cycle detection time (inside Kahn's topological sort), not on
     * any hot path.
     *
     * <p>Example message generated:
     * <pre>
     *   EX-BOOT-0001: Circular dependency detected between subsystems:
     *   [persistence → graph → events → persistence].
     *   The Exeris Kernel cannot boot. Review SubsystemProvider.getSubsystems()
     *   and remove the cycle.
     * </pre>
     *
     * @param members the subsystem names that form the cycle (insertion-ordered)
     * @return a new exception instance with full diagnostic message; if {@code members}
     *         is {@code null} or empty (diagnostic detail unavailable), returns
     *         {@link #SENTINEL} to prevent secondary heap allocations during an
     *         Entropy Panic state — no {@code IllegalArgumentException} is thrown
     */
    public static SubsystemCircularDependencyException forCycle(Collection<String> members) {
        if (members == null || members.isEmpty()) {
            return SENTINEL;
        }
        Set<String> ordered = new LinkedHashSet<>(members);
        String chain = String.join(" → ", ordered)
                + " → " + ordered.iterator().next(); // close the cycle visually
        String message = ERROR_CODE + ": Circular dependency detected between subsystems: ["
                + chain + "]. "
                + "The Exeris Kernel cannot boot. "
                + "Review SubsystemProvider.getSubsystems() and remove the cycle.";
        return new SubsystemCircularDependencyException(message, ordered);
    }

    /**
     * Returns the set of subsystem names involved in the detected cycle.
     *
     * @return immutable, insertion-ordered set; empty for {@link #SENTINEL}
     */
    public Set<String> cycleMembers() {
        return cycleMembers;
    }

}

