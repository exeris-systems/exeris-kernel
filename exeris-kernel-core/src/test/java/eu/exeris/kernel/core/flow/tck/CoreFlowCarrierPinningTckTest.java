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
import eu.exeris.kernel.tck.contract.flow.FlowCarrierPinningTck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

/**
 * Performance contract TCK binding: advisory carrier-thread pinning verification for the Core flow subsystem.
 *
 * <h2>Status</h2>
 * <p><b>Enabled — Advisory (Core-tier capability):</b><br/>
 * This advisory binding stays enabled to validate that the Core tier remains within its documented
 * pinning tolerance. Core uses standard virtual threading without stronger scheduler guarantees,
 * so carrier-pinning resilience is advisory rather than a hard zero-pinning contract.
 *
 * <h2>Advisory Scope</h2>
 * <p>See {@link eu.exeris.kernel.spi.flow.FlowEngineCapabilities} and
 * <a href="docs/adr/ADR-007 Next-Gen Runtime Architecture.md">ADR-007 Performance Tiers</a>:
 * <ul>
 *   <li><b>Core:</b> Baseline orchestration; no carrier pinning guarantees.</li>
 *   <li><b>Community+:</b> Tries to avoid blocking operations on hot paths; may park carrier transiently.</li>
 *   <li><b>Enterprise:</b> Strict no carrier pinning + JFR evidence of carrier thread freedom.</li>
 * </ul>
 *
 * <h2>Carrier Pinning Context</h2>
 * <p>When a virtual thread (VT) executes code that holds a monitor lock (synchronized block,
 * ReentrantLock, or native code), the underlying platform "carrier" thread (OS thread in the
 * ForkJoinPool) is pinned and cannot yield to other VTs. This reduces concurrency.
 *
 * <p>To measure pinning, the TCK runs {@code scheduler.schedule(plan, ctx)} from 1,000 concurrent VTs
 * and inspects JFR events for {@code jdk.VirtualThreadPinned} exceeding the 20 ms threshold.
 *
 * <h2>What This Test Verifies</h2>
 * <p>This advisory test:
 * <ol>
 *   <li>Bootstraps a {@link FlowEngine} with the Core binding.</li>
 *   <li>Compiles a simple two-step Saga definition.</li>
 *   <li>Spawns warm-up and steady-state virtual threads that call
 *       {@code scheduler.schedule(plan, ctx)}.</li>
 *   <li>Checks that observed carrier pinning stays within the Core tier's advisory tolerance.</li>
 * </ol>
 *
 * <h2>Future Tightening</h2>
 * <p>If the Core tier later adopts stronger pinning guarantees, this advisory binding can be
 * tightened to enforce a stricter threshold without changing its role as the Core test hook.
 *
 * <h2>References</h2>
 * <ul>
 *   <li>[ADR-007] docs/adr/ADR-007 Next-Gen Runtime Architecture.md § Performance Tiers</li>
 *   <li>[Perf Contract] docs/performance-contract.md § Carrier Pinning Resilience</li>
 *   <li>[Flow Subsystem] docs/subsystems/flow.md</li>
 *   <li>[JFR Event] jdk.VirtualThreadPinned (Java 21+)</li>
 * </ul>
 *
 * @since 0.5.0
 * @see eu.exeris.kernel.tck.contract.flow.FlowCarrierPinningTck
 * @see CoreFlowEngine
 */
@DisplayName("Core: Flow carrier pinning TCK [ADVISORY]")
@Tag("perf-contract")
@Tag("advisory")
class CoreFlowCarrierPinningTckTest extends FlowCarrierPinningTck {

    /**
     * Wires the Core flow engine for this test.
     * Uses COMMUNITY tier capabilities since Core implements the base feature set.
     */
    @Override
    protected FlowEngine createEngine() {
        FlowEngineConfig config = FlowEngineConfig.defaults("CoreFlowEngine/CarrierPinningTck");
        return new CoreFlowEngine(config, FlowEngineCapabilities.COMMUNITY.withProvider("core-flow-carrier-tck"));
    }
}
