/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.telemetry;

import eu.exeris.kernel.spi.telemetry.TelemetrySink;
import eu.exeris.kernel.tck.perf.AbstractTelemetrySinkBenchmark;

/**
 * JMH Benchmark: Community {@link JfrTelemetrySink} emission throughput.
 *
 * <h2>What is measured</h2>
 * <p>Inherited benchmarks from {@link AbstractTelemetrySinkBenchmark}:
 * <ul>
 *   <li>{@code emitInfoThroughput} — {@code KernelLifecycleEvent.commit()} via generic path.</li>
 *   <li>{@code emitWarnThroughput} — WARN with null exception; single lifecycle event.</li>
 *   <li>{@code noOpSinkBaseline} — irreducible dispatch overhead.</li>
 * </ul>
 *
 * <h2>Community standard sink path</h2>
 * <p>{@link JfrTelemetrySink} is the canonical Community sink and delegates to the
 * standard core JFR implementation. This benchmark tracks the effective throughput
 * of the Community-facing JFR sink binding.
 *
 * <h2>SLO</h2>
 * <ul>
 *   <li>INFO path (isEnabled() = false): {@code ≥ 20 000 000 ops/s}.</li>
 *   <li>INFO path (active recording): {@code ≥ 2 000 000 ops/s}.</li>
 * </ul>
 *
 * @since 0.5.0
 */
public class CommunityJfrSinkBenchmark extends AbstractTelemetrySinkBenchmark {

    @Override
    protected TelemetrySink createSink() {
        return new JfrTelemetrySink();
    }
}

