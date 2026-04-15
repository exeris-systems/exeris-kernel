/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
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
 * Performance contract TCK binding: zero-allocation verification for Core flow subsystem.
 *
 * <h2>Status</h2>
 * <p><b>DISABLED — Advisory (Core-tier capability):</b><br/>
 * The Core flow subsystem does not guarantee zero-allocation on the step-transition hot path.
 * Core uses unrestricted virtual threading with standard heap allocations for intermediate
 * state. Zero-allocation is a Community-tier and above SLO.
 *
 * <h2>Why Disabled</h2>
 * <p>See {@link eu.exeris.kernel.spi.flow.FlowEngineCapabilities} and
 * <a href="docs/adr/ADR-007 Next-Gen Runtime Architecture.md">ADR-007 Performance Tiers</a>:
 * <ul>
 *   <li><b>Core:</b> Baseline orchestration; no performance guarantees.</li>
 *   <li><b>Community+:</b> Bounded heap allocation (typically ~5 B/op); target zero-alloc in steady state.</li>
 *   <li><b>Enterprise:</b> Strict zero-allocation + carrier pinning avoidance on hot paths.</li>
 * </ul>
 *
 * <h2>What This Test Would Verify</h2>
 * <p>If enabled (e.g., for a future Community-tier flow implementation), this test would:
 * <ol>
 *   <li>Bootstrap a {@link FlowEngine} with the Core binding.</li>
 *   <li>Compile a simple two-step Saga definition.</li>
 *   <li>Run {@code scheduler.schedule(plan, ctx)} in a tight loop (10k iterations).</li>
 *   <li>Assert zero {@code eu.exeris.*} heap allocations via JFR (if {@code supportsZeroGcHotPath() == true}).</li>
 * </ol>
 *
 * <h2>Path to Enablement</h2>
 * <p>To enable this test:
 * <ol>
 *   <li>Refactor Core flow step transitions to use pre-allocated object pools or Arena-based allocation.</li>
 *   <li>Verify with JFR that no transient objects are created during
 *       {@code schedule → park → wake} sequences.</li>
 *   <li>Change this class's {@link #supportsZeroGcHotPath()} to return {@code true}.</li>
 *   <li>Remove {@code @Disabled} and update {@code @DisplayName}.</li>
 * </ol>
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
