/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.spi.bootstrap.Subsystem;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.tck.contract.bootstrap.AbstractSubsystemLifecycleTck;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Binds the subsystem lifecycle contract to {@link CommunityMemorySubsystem}, and asserts the one
 * thing the contract cannot: that {@code stop()} actually released the allocator.
 *
 * <p>The subsystem under test owns the kernel's {@link MemoryAllocator}, and
 * {@code SubsystemOrchestrator.shutdown()} calls {@code stop()} only for a subsystem reporting
 * {@code isRunning()}. The {@code memoryAllocator.close()} in that method is the only call of its
 * kind in this repository, so the lifecycle contract is what stands between this tier and an arena
 * leak for the life of the JVM. The orchestrator's own shutdown tests cannot cover it: they drive a
 * {@code tracking("memory", …)} fake, not this class.
 *
 * <p>No {@code withLifecycleContext} override: {@code initialize()} reads
 * {@code KernelProviders.CURRENT_CONFIG} through an {@code isBound()} check and falls back to
 * {@code MemoryProviderConfig.defaults()}, so the unbound case is a supported configuration rather
 * than a gap in this test.
 */
@DisplayName("Community: CommunityMemorySubsystem lifecycle TCK")
class CommunityMemorySubsystemLifecycleTckTest extends AbstractSubsystemLifecycleTck {

    @Override
    protected Subsystem createSubsystem() {
        return new CommunityMemorySubsystem();
    }

    @Test
    @DisplayName("stop() closes the allocator the subsystem owns")
    void stopClosesTheAllocator() {
        CommunityMemorySubsystem subsystem = new CommunityMemorySubsystem();
        subsystem.initialize();
        subsystem.start();

        MemoryAllocator allocator = boundAllocator(subsystem);
        assertThat(allocator)
                .withFailMessage("the subsystem bound no allocator, so the assertion below would "
                        + "have nothing to observe being closed")
                .isNotNull();

        subsystem.stop();

        // Closed is not directly observable, so this reads the state through the behaviour the
        // allocator's own contract documents: every allocate* method throws IllegalStateException
        // once closed.
        assertThatIllegalStateException()
                .isThrownBy(() -> allocator.allocateNetwork(64))
                .withMessageContaining("closed");
    }

    /**
     * The allocator this subsystem publishes through its carrier bindings.
     *
     * @param subsystem an initialised subsystem
     * @return the bound allocator, or {@code null} if it bound none
     */
    private static MemoryAllocator boundAllocator(CommunityMemorySubsystem subsystem) {
        AtomicReference<MemoryAllocator> bound = new AtomicReference<>();
        subsystem.providerBindings()
                .apply(ScopedValue.where(KernelProviders.CURRENT_CONFIG, null))
                .run(() -> bound.set(KernelProviders.MEMORY_ALLOCATOR.isBound()
                        ? KernelProviders.MEMORY_ALLOCATOR.get()
                        : null));
        return bound.get();
    }
}
