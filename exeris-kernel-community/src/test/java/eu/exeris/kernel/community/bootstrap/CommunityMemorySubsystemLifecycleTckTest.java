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
 * <p>This binding exists because its absence was load-bearing. {@code CommunityMemorySubsystem}
 * implemented {@link Subsystem} directly and overrode nothing, so {@code isRunning()} answered the
 * interface default {@code false} — which tells {@code SubsystemOrchestrator.shutdown()} there is
 * nothing to stop. The {@code memoryAllocator.close()} in {@code stop()} is the only call of its
 * kind in this repository and had therefore never run in any process. Nothing caught it: the
 * orchestrator's shutdown tests use a {@code tracking("memory", …)} fake that <em>does</em> report
 * running, and the one test that touched the real class never shut anything down.
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
        // allocator's own contract documents for it: every allocate* method throws once closed.
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
