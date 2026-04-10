/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.spi.flow;

/**
 * SPI: Step-level idempotency guard for flow execution.
 *
 * <h2>Intent</h2>
 * <p>Prevents duplicate step execution when a flow instance is rescheduled
 * (e.g., after a wake from choreography or a crash-recovery replay).
 * Implementations MUST be thread-safe and support concurrent claims.
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
 * @since 0.5.0
 * @see eu.exeris.kernel.spi.context.KernelProviders#IDEMPOTENCY_GUARD
 */
public interface IdempotencyGuard {

    /**
     * Attempts to claim exclusive execution rights for the identified step.
     *
     * <p>Uses compare-and-set semantics: the first caller for a given
     * {@code (instanceIdMost, instanceIdLeast, stepIndex)} tuple wins and receives
     * {@code true}. All subsequent callers receive {@code false} until
     * {@link #releaseInstance} is called.
     *
     * @param instanceIdMost  most-significant bits of the flow instance UUID
     * @param instanceIdLeast least-significant bits of the flow instance UUID
     * @param stepIndex       zero-based step index within the execution plan
     * @return {@code true} if the claim was acquired; {@code false} if already claimed
     */
    boolean tryClaimStep(long instanceIdMost, long instanceIdLeast, int stepIndex);

    /**
     * Releases all step claims for the identified instance.
     *
     * <p>Called when the instance reaches a terminal state (COMPLETED or FAILED).
     * After this call, {@link #tryClaimStep} will return {@code true} again for
     * all step indices of this instance.
     *
     * @param instanceIdMost  most-significant bits of the flow instance UUID
     * @param instanceIdLeast least-significant bits of the flow instance UUID
     */
    void releaseInstance(long instanceIdMost, long instanceIdLeast);
}
