/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.flow;

/**
 * SPI: Step-level idempotency guard for flow execution.
 *
 * <h2>Intent</h2>
 * <p>Keeps a step from running twice when a flow instance is rescheduled onto it — after a
 * choreography wake, or a crash-recovery replay that resumes from a checkpoint written before the
 * step's effects were durable.
 *
 * <h2>Discovery &amp; Wiring</h2>
 * <p>The bootstrapper may bind an {@code IdempotencyGuard} to
 * {@link eu.exeris.kernel.spi.context.KernelProviders#IDEMPOTENCY_GUARD}
 * before calling {@link FlowEngine#start()}. If the slot is unbound, the engine
 * falls back to a default heap-based implementation.
 *
 * <h2>The Wall (SPI Compliance)</h2>
 * <p>This interface is <strong>implementation-blind</strong>: no reference to any
 * storage driver, lock type, or concrete data structure appears here.
 *
 * <p><b>Thread confinement:</b> any thread — a guard is shared across every flow the engine runs
 * and must be safe for concurrent claims from any of them.
 * <p><b>Ownership:</b> the guard owns the claims it records; a claim is held from the winning
 * {@link #tryClaimStep} until {@link #releaseInstance} for that instance, which the engine calls
 * when the instance reaches a terminal state. Callers release nothing else.
 *
 * @implSpec Implementations must be thread-safe and support concurrent claims — two threads racing
 *           on the same {@code (instanceIdMost, instanceIdLeast, stepIndex)} tuple must produce
 *           exactly one winner.
 * @since 0.5
 * @see eu.exeris.kernel.spi.context.KernelProviders#IDEMPOTENCY_GUARD
 */
public interface IdempotencyGuard {

    /**
     * Attempts to claim exclusive execution rights for the identified step.
     *
     * <p>Compare-and-set semantics: the first caller for a given
     * {@code (instanceIdMost, instanceIdLeast, stepIndex)} tuple wins and receives
     * {@code true}. Every later caller receives {@code false} until
     * {@link #releaseInstance} is called for that instance.
     *
     * @param instanceIdMost  most-significant bits of the flow instance UUID
     * @param instanceIdLeast least-significant bits of the flow instance UUID
     * @param stepIndex       zero-based step index within the execution plan
     * @return {@code true} if this caller now holds the claim and must run the step;
     *         {@code false} if the tuple was already claimed and the step must be skipped
     */
    boolean tryClaimStep(long instanceIdMost, long instanceIdLeast, int stepIndex);

    /**
     * Drops every claim recorded for the instance, so the guard stops carrying state for a flow
     * that has finished.
     *
     * <p>The engine calls this when the instance reaches a terminal state. Afterwards
     * {@link #tryClaimStep} returns {@code true} again for every step index of this instance.
     *
     * @param instanceIdMost  most-significant bits of the flow instance UUID
     * @param instanceIdLeast least-significant bits of the flow instance UUID
     */
    void releaseInstance(long instanceIdMost, long instanceIdLeast);
}
