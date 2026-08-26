/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.spi.flow.model;

/**
 * Runtime flow state — represents the lifecycle state of a single flow instance.
 *
 * <h2>State Machine</h2>
 * <pre>
 *   CREATED ──► RUNNING ──► COMPLETED
 *                  │
 *                  ├──► PARKED ──► RUNNING  (wake)
 *                  │
 *                  └──► COMPENSATING ──► FAILED_ROLLEDBACK
 * </pre>
 *
 * @since 0.5.0
 */
public enum FlowState {

    /** Flow has been allocated but execution has not yet started. */
    CREATED(0),

    /** Flow is actively executing steps. */
    RUNNING(1),

    /**
     * Flow is suspended, waiting for an external event.
     * Will be resumed via {@link eu.exeris.kernel.spi.flow.FlowScheduler#wake(FlowContext)}.
     */
    PARKED(2),

    /** Flow has completed all steps successfully. */
    COMPLETED(3),

    /** Flow has encountered a failure and is executing backward compensation steps. */
    COMPENSATING(4),

    /** Flow has completed backward compensation after a failure. Terminal state. */
    FAILED_ROLLEDBACK(5);

    /** Integer code stored off-heap in the flow context slab. */
    public final int code;

    FlowState(int code) {
        this.code = code;
    }

    /**
     * Resolves a {@link FlowState} from its integer code.
     *
     * @param code the integer code read from the off-heap slab
     * @return corresponding {@link FlowState}
     * @throws IllegalArgumentException if the code is unknown
     */
    public static FlowState fromCode(int code) {
        return switch (code) {
            case 0 -> CREATED;
            case 1 -> RUNNING;
            case 2 -> PARKED;
            case 3 -> COMPLETED;
            case 4 -> COMPENSATING;
            case 5 -> FAILED_ROLLEDBACK;
            default -> throw new IllegalArgumentException("Unknown FlowState code: " + code);
        };
    }

    /** Returns {@code true} if this is a terminal state (no further transitions possible). */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED_ROLLEDBACK;
    }
}

