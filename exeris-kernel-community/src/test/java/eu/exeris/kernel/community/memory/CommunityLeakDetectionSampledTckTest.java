/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.memory;

import eu.exeris.kernel.spi.memory.MemoryProvider;
import eu.exeris.kernel.tck.contract.memory.AbstractLeakDetectionSampledTck;
import org.junit.jupiter.api.DisplayName;

/**
 * Community concrete TCK: {@link AbstractLeakDetectionSampledTck} backed by
 * {@link CommunityMemoryProvider}.
 *
 * <h2>What this proves for Community tier</h2>
 * <p>Verifies that {@link eu.exeris.kernel.spi.memory.LeakDetectionMode#SAMPLED} mode
 * in the Community allocator:
 * <ul>
 *   <li>Reports the correct mode via {@code stats().leakDetectionMode()}.</li>
 *   <li>Does not produce false positives for properly-closed buffers.</li>
 *   <li>Remains correct under 10 000 concurrent Virtual Threads.</li>
 * </ul>
 *
 * <h2>Implementation path</h2>
 * <p>{@link CommunityMemoryAllocator} delegates to {@link eu.exeris.kernel.core.memory.LeakTracker}
 * (1-in-128 sampling via atomic counter + {@link java.lang.ref.Cleaner} phantom reference).
 * Properly-closed buffers call {@link eu.exeris.kernel.core.memory.LeakTracker.LeakHandle#cancel()}
 * before the JVM can observe them as phantom-reachable — so {@code leakCount()} stays zero.
 *
 * @since 0.5.0
 */
@DisplayName("Community: LeakDetection SAMPLED TCK")
class CommunityLeakDetectionSampledTckTest extends AbstractLeakDetectionSampledTck {

    @Override
    protected MemoryProvider createProvider() {
        return new CommunityMemoryProvider();
    }
}
