/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.community.memory.CommunityMemoryBudgetResolver;
import eu.exeris.kernel.community.transport.MapConfigProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The budget an operator configures is the one the allocator the subsystem binds reports. A resolver
 * that reads the key proves nothing on its own; this asserts the value on the allocator the kernel uses.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
@DisplayName("Community: memory subsystem honours memory.offHeapBudgetBytes")
class CommunityMemorySubsystemBudgetTest {

    private static long boundTotalBytes(MapConfigProvider config) {
        CommunityMemorySubsystem subsystem = new CommunityMemorySubsystem();
        AtomicReference<Long> total = new AtomicReference<>();
        ScopedValue.where(KernelProviders.CURRENT_CONFIG, config).run(subsystem::initialize);
        try {
            subsystem.providerBindings()
                    .apply(ScopedValue.where(KernelProviders.CURRENT_CONFIG, config))
                    .run(() -> total.set(KernelProviders.MEMORY_ALLOCATOR.get().stats().totalBytes()));
        } finally {
            subsystem.start();
            subsystem.stop();
        }
        return total.get();
    }

    @Test
    @DisplayName("a configured budget is what the bound allocator reports as its total")
    void configuredBudgetReachesTheAllocator() {
        assertThat(boundTotalBytes(MapConfigProvider.ofLongs(
                Map.of(CommunityMemoryBudgetResolver.KEY, 1_048_576L)))).isEqualTo(1_048_576L);
    }

    @Test
    @DisplayName("with the key absent the allocator reports no budget")
    void absentKeyReportsNoBudget() {
        assertThat(boundTotalBytes(MapConfigProvider.ofLongs(Map.of()))).isEqualTo(-1L);
    }
}
