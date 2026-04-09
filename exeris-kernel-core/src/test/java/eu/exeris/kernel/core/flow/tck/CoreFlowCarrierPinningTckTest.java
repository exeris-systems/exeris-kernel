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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;

/**
 * Performance contract TCK binding: carrier thread pinning verification for Core flow subsystem.
 *
 * <h2>Status</h2>
 * <p><b>DISABLED — Advisory (Core-tier capability):</b><br/>
 * The Core flow subsystem does not provide carrier pinning avoidance on the step-transition hot path.
 * Core uses standard virtual threading without optimized scheduler hints to keep carrier threads
 * free for other tasks. Carrier pinning guidance is a Community-tier and above optimization.
 *
 * <h2>Why Disabled</h2>
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
 * <h2>What This Test Would Verify</h2>
 * <p>If enabled (e.g., for a future Community-tier flow implementation), this test would:
 * <ol>
 *   <li>Bootstrap a {@link FlowEngine} with the Core binding.</li>
 *   <li>Compile a simple two-step Saga definition.</li>
 *   <li>Spawn 200 warm-up VTs and 1,000 steady-state VTs, each calling
 *       {@code scheduler.schedule(plan, ctx)}.</li>
 *   <li>Record JFR events and assert zero carrier pinning > 20 ms.</li>
 * </ol>
 *
 * <h2>Path to Enablement</h2>
 * <p>To enable this test:
 * <ol>
 *   <li>Audit Core flow scheduler implementation to remove any blocking operations
 *       (synchronized, monitor locks) on hot paths.</li>
 *   <li>Replace blocking coordination with lock-free or scoped-lock mechanisms
 *       (VarHandle CAS, StampedLock, or LockSupport.parkNanos with no monitor).</li>
 *   <li>Verify with JFR that no {@code jdk.VirtualThreadPinned} events are recorded
 *       during steady-state Saga step transitions.</li>
 *   <li>Update this class to return {@code true} from a carrier-pinning guarantee method
 *       (if such override is added to the base TCK).</li>
 *   <li>Remove {@code @Disabled} and update {@code @DisplayName}.</li>
 * </ol>
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
@Disabled("Core-tier flow—carrier pinning avoidance not guaranteed. See ADR-007 Performance Tiers.")
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
