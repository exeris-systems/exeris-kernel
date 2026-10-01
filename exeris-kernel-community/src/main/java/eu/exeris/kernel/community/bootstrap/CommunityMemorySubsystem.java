/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.community.telemetry.CommunityJfrEventCatalogue;
import eu.exeris.kernel.spi.bootstrap.BootstrapPhase;
import eu.exeris.kernel.spi.bootstrap.Subsystem;
import eu.exeris.kernel.spi.config.ConfigProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.memory.LeakDetectionMode;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProvider;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Community FOUNDATION subsystem that initialises the memory tier.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@link #initialize()} — creates the {@link CommunityMemoryProvider} and its
 *       {@link MemoryAllocator} from the active kernel config; binds both into the
 *       scoped-value carrier via {@link #providerBindings()}.</li>
 *   <li>{@link #start()} — warms this tier's hot-path JFR event classes and marks the subsystem
 *       running; the allocator itself is ready from {@code initialize()}.</li>
 *   <li>{@link #stop()} — closes the allocator, releasing all per-buffer arenas.</li>
 * </ol>
 *
 * <p>Extends {@link AbstractCommunitySubsystem} for its running flag, and that is load-bearing
 * rather than tidiness. Two things read {@link Subsystem#isRunning()} and both go silent when it
 * answers the interface default {@code false}: {@code SubsystemOrchestrator.shutdown()} skips the
 * subsystem, so the {@code memoryAllocator.close()} below never runs and every arena this tier holds
 * leaks for the life of the JVM; and the orchestrator gates the Core warm-up on the same answer, so
 * the Core {@code memory} event group — the allocation events, the hottest in the catalogue — is
 * never warmed. A subsystem here that holds a resource must report through {@code markRunning}.
 *
 * @since 0.5
 */
final class CommunityMemorySubsystem extends AbstractCommunitySubsystem {

    private MemoryProvider  memoryProvider;
    private MemoryAllocator memoryAllocator;

    @Override
    public String name() {
        return "memory";
    }

    @Override
    public List<String> dependsOn() {
        return List.of();
    }

    @Override
    public BootstrapPhase phase() {
        return BootstrapPhase.FOUNDATION;
    }

    @Override
    public void initialize() {
        ConfigProvider config = KernelProviders.CURRENT_CONFIG.isBound()
            ? KernelProviders.CURRENT_CONFIG.get()
            : null;
        MemoryProviderConfig providerConfig = buildConfig(config);
        memoryProvider  = new CommunityMemoryProvider();
        memoryAllocator = memoryProvider.createAllocator(providerConfig);
    }

    @Override
    public void start() {
        if (memoryAllocator != null) {
            // This driver's hot-path JFR event classes initialise here, on the thread that starts
            // the subsystem: a virtual thread inside a <clinit> pins its carrier for the whole of
            // it. Behind the same check markRunning takes, as every other Community subsystem does.
            //
            // This tier has no provider to fail to find — initialize() always builds an allocator —
            // so the guard reads as start() reached without initialize(). That is the only state in
            // which there is nothing to warm and nothing to stop.
            CommunityJfrEventCatalogue.warmHotPath(name());
        }
        // The allocator needs nothing else started; what start() owes is the running flag, because
        // stop() below has a resource to release and the orchestrator reads isRunning() to decide
        // whether to call it.
        markRunning(memoryAllocator != null);
    }

    @Override
    public void stop() {
        if (memoryAllocator != null) {
            memoryAllocator.close();
        }
        markRunning(false);
    }

    @Override
    public UnaryOperator<ScopedValue.Carrier> providerBindings() {
        return CommunityCarrierBindings.operator(
            CommunityCarrierBindings.binding(KernelProviders.MEMORY_PROVIDER, memoryProvider),
            CommunityCarrierBindings.binding(KernelProviders.MEMORY_ALLOCATOR, memoryAllocator)
        );
    }

    private static MemoryProviderConfig buildConfig(ConfigProvider config) {
        if (config == null) {
            return MemoryProviderConfig.defaults();
        }

        int carriers = Runtime.getRuntime().availableProcessors();
        ConfigProvider.KernelSettings settings = config.kernelSettings().get();
        MemoryProviderConfig defaults = MemoryProviderConfig.defaults();
        return new MemoryProviderConfig(
                -1L,
                defaults.networkOffHeapThreshold(),
                carriers,
                LeakDetectionMode.DISABLED,
                settings.telemetry().jfrEnabled()
        );
    }
}
