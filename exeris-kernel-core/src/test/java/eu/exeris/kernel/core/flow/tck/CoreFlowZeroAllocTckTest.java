/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.core.flow.tck;

import eu.exeris.kernel.core.flow.CoreFlowEngine;
import eu.exeris.kernel.spi.flow.FlowEngine;
import eu.exeris.kernel.spi.flow.FlowEngineCapabilities;
import eu.exeris.kernel.spi.flow.FlowEngineConfig;
import eu.exeris.kernel.tck.contract.flow.FlowZeroAllocTck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

/**
 * Performance contract TCK binding: advisory zero-allocation verification for the Core flow subsystem.
 *
 * <h2>Status</h2>
 * <p><b>Enabled — Advisory (Core-tier capability):</b><br/>
 * This advisory binding remains active to document and verify the Core tier's bounded-allocation
 * behavior. Core does not guarantee zero-allocation on the step-transition hot path and uses
 * standard heap-backed orchestration for intermediate state.
 *
 * <h2>Advisory Scope</h2>
 * <p>See {@link eu.exeris.kernel.spi.flow.FlowEngineCapabilities} and
 * <a href="docs/adr/ADR-007 Next-Gen Runtime Architecture.md">ADR-007 Performance Tiers</a>:
 * <ul>
 *   <li><b>Core:</b> Baseline orchestration; no performance guarantees.</li>
 *   <li><b>Community+:</b> Bounded heap allocation (typically ~5 B/op); target zero-alloc in steady state.</li>
 *   <li><b>Enterprise:</b> Strict zero-allocation + carrier pinning avoidance on hot paths.</li>
 * </ul>
 *
 * <h2>What This Test Verifies</h2>
 * <p>This advisory test:
 * <ol>
 *   <li>Bootstraps a {@link FlowEngine} with the Core binding.</li>
 *   <li>Compiles a simple two-step Saga definition.</li>
 *   <li>Runs {@code scheduler.schedule(plan, ctx)} in a tight loop.</li>
 *   <li>Confirms the Core tier stays within its documented bounded-allocation budget.</li>
 * </ol>
 *
 * <h2>Future Tightening</h2>
 * <p>If the Core tier later adopts stricter zero-allocation guarantees, this binding can tighten
 * its budget by updating {@link #supportsZeroGcHotPath()} and
 * {@link #maxExerisAllocationsPerIteration()} accordingly.
 *
 * <h2>References</h2>
 * <ul>
 *   <li>[ADR-007] docs/adr/ADR-007 Next-Gen Runtime Architecture.md § Performance Tiers</li>
 *   <li>[Perf Contract] docs/performance-contract.md § Allocation Rate: 0 B / req (Enterprise)</li>
 *   <li>[Flow Subsystem] docs/subsystems/flow.md</li>
 * </ul>
 *
 * @since 0.5.0
 * @see eu.exeris.kernel.tck.contract.flow.FlowZeroAllocTck
 * @see CoreFlowEngine
 */
@DisplayName("Core: Flow zero-allocation TCK [ADVISORY]")
@Tag("perf-contract")
@Tag("advisory")
class CoreFlowZeroAllocTckTest extends FlowZeroAllocTck {

    /**
     * Wires the Core flow engine for this test.
     * Uses COMMUNITY tier capabilities since Core implements the base feature set.
     */
    @Override
    protected FlowEngine createEngine() {
        FlowEngineConfig config = FlowEngineConfig.defaults("CoreFlowEngine/ZeroAllocTck");
        return new CoreFlowEngine(config, FlowEngineCapabilities.COMMUNITY.withProvider("core-flow-zero-alloc-tck"));
    }

    /**
     * Core tier does not support zero-allocation hot paths.
     * Returns {@code false} to indicate bounded (non-zero) allocations are expected.
     *
     * @return always {@code false} for Core tier
     */
    @Override
    protected boolean supportsZeroGcHotPath() {
        return false;
    }

    /**
     * Maximum allowed heap allocations per hot-path iteration for Core tier.
     * Core flow may allocate intermediate objects during step transitions.
     *
     * @return budget of 10 allocations per iteration (typical for unoptimized orchestration)
     */
    @Override
    protected int maxExerisAllocationsPerIteration() {
        return 10;
    }
}
