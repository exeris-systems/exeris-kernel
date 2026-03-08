/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.tck.contract;

import eu.exeris.kernel.tck.contract.JfrAllocationMonitor.Config;
import eu.exeris.kernel.tck.contract.JfrAllocationMonitor.Result;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * Abstract base for all subsystem-specific JFR Zero-Allocation TCKs.
 *
 * <h2>Template Method Pattern</h2>
 * <p>Subclasses define the "what" (which subsystem, which hot path).
 * This class controls the "how" (three-phase JFR protocol, tier assertion).
 *
 * <h2>Subclass contract — override these:</h2>
 * <ul>
 *   <li>{@link #subsystemName()} — e.g. {@code "Transport"}, {@code "Telemetry"}</li>
 *   <li>{@link #hotPathDescription()} — human-readable, e.g. {@code "queueWrite(buf)"}</li>
 *   <li>{@link #bootstrapSubsystem()} — create engines, allocators, sinks <em>before</em> JFR</li>
 *   <li>{@link #runSingleIteration()} — one hot-path iteration (called N times inside JFR window)</li>
 *   <li>{@link #tearDownSubsystem()} — release resources after measurement</li>
 * </ul>
 *
 * <h2>Optionally override:</h2>
 * <ul>
 *   <li>{@link #supportsZeroGcHotPath()} — {@code true} for Enterprise tier (default: {@code false})</li>
 *   <li>{@link #maxExerisAllocationsPerIteration()} — Community budget (default: 5)</li>
 *   <li>{@link #warmupIterations()} — default: 1 000</li>
 *   <li>{@link #hotPathIterations()} — default: 10 000</li>
 * </ul>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * public abstract class TransportZeroAllocTck extends AbstractSubsystemZeroAllocTck {
 *
 *     protected abstract TransportEngine createEngine();
 *     private TransportEngine engine;
 *
 *     @Override protected String subsystemName()      { return "Transport"; }
 *     @Override protected String hotPathDescription()  { return "queueWrite(buf)"; }
 *
 *     @Override protected void bootstrapSubsystem()    { engine = createEngine(); }
 *     @Override protected void runSingleIteration()    { engine.doWork(); }
 *     @Override protected void tearDownSubsystem()     { engine.close(); }
 * }
 * }</pre>
 *
 * @see JfrAllocationMonitor
 * @since 0.5.0
 */
public abstract class AbstractSubsystemZeroAllocTck {

    // =========================================================================
    // Template methods — MUST override
    // =========================================================================

    /**
     * Human-readable subsystem name (e.g. {@code "Transport"}, {@code "Graph"}).
     */
    protected abstract String subsystemName();

    /**
     * Human-readable hot-path description for assertion messages.
     */
    protected abstract String hotPathDescription();

    /**
     * Bootstrap phase: create all SPI objects (engines, allocators, sinks).
     * Called <em>before</em> JFR recording starts. Enterprise tier builds
     * its entire slab/pool infrastructure here.
     */
    protected abstract void bootstrapSubsystem();

    /**
     * Execute one iteration of the hot path under observation.
     * Called N times inside the JFR recording window.
     *
     * <p>This is the <em>only</em> code that JFR measures. Keep it tight.
     * Do NOT create SPI objects here — that belongs in {@link #bootstrapSubsystem()}.
     */
    protected abstract void runSingleIteration();

    /**
     * Release all resources created in {@link #bootstrapSubsystem()}.
     * Called after measurement completes.
     */
    protected abstract void tearDownSubsystem();

    // =========================================================================
    // Template methods — MAY override
    // =========================================================================

    /**
     * Returns {@code true} if this tier guarantees zero {@code eu.exeris.*} heap
     * allocations on the hot path (Enterprise: slab pool, pre-allocated buffers).
     * <p>Default: {@code false} (Community tier — bounded allocations).
     */
    protected boolean supportsZeroGcHotPath() {
        return false;
    }

    /**
     * Maximum {@code eu.exeris.*} heap allocations allowed per hot-path iteration
     * for Community tier. Ignored when {@link #supportsZeroGcHotPath()} is {@code true}.
     * <p>Default: {@code 5}.
     */
    protected int maxExerisAllocationsPerIteration() {
        return 5;
    }

    /**
     * Warm-up iterations (discarded recording). Default: 1 000.
     */
    protected int warmupIterations() {
        return 1_000;
    }

    /**
     * Steady-state iterations (measured recording). Default: 10 000.
     */
    protected int hotPathIterations() {
        return 10_000;
    }

    // =========================================================================
    // JUnit lifecycle — delegates to template methods
    // =========================================================================

    @BeforeEach
    final void setUpZeroAllocTck() {
        // ── BOOTSTRAP PHASE ─────────────────────────────────────────────────
        // All SPI objects are created HERE, before any JFR recording starts.
        // Enterprise tier builds its entire slab pool and pre-allocates all
        // buffers at this point. JFR is dark.
        bootstrapSubsystem();
    }

    @AfterEach
    final void tearDownZeroAllocTck() {
        tearDownSubsystem();
    }

    // =========================================================================
    // The Test — single, final, non-overridable
    // =========================================================================

    @Test
    @DisplayName("Hot path JFR allocation profile matches tier contract")
    final void allocationProfileMatchesTierContract() throws IOException {
        Config config = new Config(
                subsystemName(),
                getClass().getSimpleName(),
                warmupIterations(),
                hotPathIterations()
        );

        Result result = JfrAllocationMonitor.measure(config, iterations -> {
            for (int i = 0; i < iterations; i++) {
                runSingleIteration();
            }
        });

        if (supportsZeroGcHotPath()) {
            JfrAllocationMonitor.assertZeroExerisAllocations(result, hotPathDescription());
        } else {
            JfrAllocationMonitor.assertBoundedExerisAllocations(
                    result,
                    hotPathIterations(),
                    maxExerisAllocationsPerIteration(),
                    hotPathDescription()
            );
        }
    }
}
