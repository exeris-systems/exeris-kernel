/*
 * Copyright (C) 2025-2026 Exeris. All rights reserved.
 *
 * This code is part of the Exeris Systems.
 * Distributed under the proprietary Exeris Software License.
 * Unauthorized copying or distribution is prohibited.
 */
package eu.exeris.kernel.spi.flow.model;

/**
 * SPI: Runtime context for a single flow instance.
 *
 * <h2>Flyweight Contract — Zero-GC in Enterprise</h2>
 * <p>This is an <b>interface</b>, not a record. Enterprise uses the Flyweight pattern:
 * one reusable per-carrier {@code FlowContextView} object that slides its {@code baseAddress}
 * over the off-heap context slab. No object is created per dispatch — preserving
 * the Zero-GC contract after {@link eu.exeris.kernel.spi.flow.FlowEngine#start()}.
 * Community uses a simple {@code HeapFlowContext record} — acceptable, no zero-GC claim.
 *
 * <h2>Identity — FORBIDDEN operations</h2>
 * <p>Do NOT use {@code ==}, {@code synchronized}, or {@code System.identityHashCode()}.
 * Use {@link #isSameInstance(FlowContext)} for identity checks.
 *
 * @since 0.5.0
 */
public interface FlowContext {

    /** Most significant 64 bits of the 128-bit flow instance UUID. Off-heap offset 0. */
    long instanceIdMost();

    /** Least significant 64 bits of the 128-bit flow instance UUID. Off-heap offset 8. */
    long instanceIdLeast();

    /** Name of the {@link FlowDefinition} this instance was compiled from. */
    String definitionName();

    /** Index of the step currently being executed. Off-heap offset 16. */
    int currentStep();

    /** Current lifecycle state. Off-heap offset 20 (stored as int code). */
    FlowState state();

    /** Absolute deadline in nanoseconds (System.nanoTime epoch). Off-heap offset 32. */
    long timeoutNanos();

    /** Safe identity comparison — use instead of {@code ==}. */
    default boolean isSameInstance(FlowContext other) {
        return other != null
               && this.instanceIdMost() == other.instanceIdMost()
               && this.instanceIdLeast() == other.instanceIdLeast();
    }
}

