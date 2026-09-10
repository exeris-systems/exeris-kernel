/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.memory;

import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.tck.contract.memory.AbstractZeroGcJfrMonitorTck;
import org.junit.jupiter.api.DisplayName;

/**
 * Community concrete TCK: {@link AbstractZeroGcJfrMonitorTck} backed by
 * {@link CommunityMemoryProvider}.
 *
 * <h2>What this proves for Community tier</h2>
 * <p>Verifies that {@code CommunityMemoryAllocator} object churn is <em>bounded</em>:
 * at most {@link #maxExerisAllocationsPerIteration()} {@code eu.exeris.*} heap objects
 * per {@code allocate()} call (a {@code CommunityLoanedBuffer} wrapper + live-set entry).
 * Runaway churn — unbounded growth, accidental {@code new Object()} in a loop, or
 * autoboxing — would fail this test.
 *
 * <p>This is an explicit design trade-off: Community favours simplicity and
 * OS-managed Arena lifecycle over zero-GC. Even at ~2–3 objects/op, Community is
 * orders of magnitude leaner than JDBC/Hibernate (hundreds of ephemeral objects per query).
 *
 * <h2>JFR artefact</h2>
 * <p>After the test, the steady-state recording is saved to
 * {@code target/jfr-reports/CommunityZeroGcJfrMonitorTckTest-&lt;timestamp&gt;.jfr}.
 * Inspect it with JDK Mission Control or:
 * <pre>{@code
 * jfr print --events jdk.ObjectAllocationInNewTLAB,jdk.ObjectAllocationSample \
 *     target/jfr-reports/CommunityZeroGcJfrMonitorTckTest-*.jfr
 * }</pre>
 *
 * @since 0.5.0
 */
@DisplayName("Community: Zero-GC JFR Monitor TCK")
class CommunityZeroGcJfrMonitorTckTest extends AbstractZeroGcJfrMonitorTck {

    @Override
    protected MemoryAllocator createAllocator() {
        return new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
    }
}
