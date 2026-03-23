/*
 * Copyright (C) 2025-2026 Exeris Systems.
 *
 * Licensed under the Apache License, Version 2.0 with Commons Clause.
 * You may use, modify, and distribute this file under those terms.
 * Commercial resale of this software as a competing product is prohibited.
 * See LICENSE-COMMUNITY in the repository root for the full text.
 */
package eu.exeris.kernel.community.transport;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.transport.TransportProvider;
import eu.exeris.kernel.tck.perf.AbstractTransportThroughputBenchmark;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.TearDown;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;

/**
 * Community transport throughput benchmark.
 */
public class CommunityTransportThroughputBenchmark extends AbstractTransportThroughputBenchmark {

    private MemoryAllocator scopedAllocator;

    @Override
    @Setup(Level.Trial)
    public void setupTransport() throws Exception {
        scopedAllocator = new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
        ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, scopedAllocator).call(() -> {
            super.setupTransport();
            return null;
        });
    }

    @Override
    @TearDown(Level.Trial)
    public void tearDownTransport() {
        super.tearDownTransport();
        scopedAllocator = null;
    }

    @Override
    protected TransportProvider getProvider() {
        return new NativeTcpTransportProvider();
    }

    @Override
    protected int benchmarkPort() {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to allocate a free loopback port for transport benchmark", exception);
        }
    }

    @Override
    protected MemoryAllocator getAllocator() {
        if (scopedAllocator != null) {
            return scopedAllocator;
        }
        return new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
    }
}
