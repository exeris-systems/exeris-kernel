/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.bootstrap;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
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
 *   <li>{@link #start()} — no-op; the allocator is ready from {@code initialize()}.</li>
 *   <li>{@link #stop()} — closes the allocator, releasing all per-buffer arenas.</li>
 * </ol>
 *
 * @since 0.5.0
 */
final class CommunityMemorySubsystem implements Subsystem {

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
        // Allocator is ready after initialize() — nothing extra to start.
    }

    @Override
    public void stop() {
        if (memoryAllocator != null) {
            memoryAllocator.close();
        }
    }

    @Override
    public UnaryOperator<ScopedValue.Carrier> providerBindings() {
        return carrier -> carrier
                .where(KernelProviders.MEMORY_PROVIDER,  memoryProvider)
                .where(KernelProviders.MEMORY_ALLOCATOR, memoryAllocator);
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
