/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.crypto;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.community.transport.TlsTestAuthority;
import eu.exeris.kernel.core.crypto.tls.TlsPeerIdentity;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.crypto.CryptoProviderConfig;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LeakDetectionMode;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.memory.MemoryStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CommunityKernelCryptoProvider#createClientTlsEngine} releases everything it built when giving
 * the engine its peer fails: the engine, its context, and every buffer it borrowed from the bound
 * allocator.
 */
@DisplayName("CommunityKernelCryptoProvider#createClientTlsEngine — a failed peer binding releases the engine")
class CommunityClientEngineAssemblyTest {

    @TempDir
    Path material;

    @Test
    @DisplayName("the binding's exception propagates and every allocation is released")
    void failedBindingReleasesTheEngine() {
        IllegalStateException refused = new IllegalStateException("binder refused");
        CommunityKernelCryptoProvider provider = new CommunityKernelCryptoProvider((engine, peer) -> {
            throw refused;
        });
        TlsTestAuthority authority = TlsTestAuthority.root(material, "assembly-ca");

        try (MemoryAllocator allocator = paranoidAllocator();
             CommunityTlsClientTrust trust = provider.openClientTrust(authority.certificate())) {
            ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, allocator).run(() ->
                    assertThatThrownBy(() -> provider.createClientTlsEngine(
                            CryptoProviderConfig.tcpClient(), trust, TlsPeerIdentity.of("localhost")))
                            .isSameAs(refused));

            MemoryStats stats = allocator.stats();
            assertThat(stats.allocationCount())
                    .as("the engine allocated its session from the bound allocator")
                    .isPositive();
            assertThat(stats.releaseCount())
                    .as("and released all of it when the binding failed")
                    .isEqualTo(stats.allocationCount());
        }
    }

    @Test
    @DisplayName("a release failure during a failed binding is attached to the binding's exception")
    void releaseFailureIsAttachedToTheBindingFailure() {
        IllegalStateException bindFailure = new IllegalStateException("binder refused after the session was allocated");
        IllegalStateException releaseFailure = new IllegalStateException("session slab release refused");
        ReleaseFailingAllocator allocator = new ReleaseFailingAllocator(paranoidAllocator(), releaseFailure);
        CommunityKernelCryptoProvider provider = new CommunityKernelCryptoProvider((engine, peer) -> {
            allocator.failSessionReleases();
            throw bindFailure;
        });
        TlsTestAuthority authority = TlsTestAuthority.root(material, "release-failure-ca");

        try (allocator; CommunityTlsClientTrust trust = provider.openClientTrust(authority.certificate())) {
            ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, allocator).run(() ->
                    assertThatThrownBy(() -> provider.createClientTlsEngine(
                            CryptoProviderConfig.tcpClient(), trust, TlsPeerIdentity.of("localhost")))
                            .isSameAs(bindFailure));

            assertThat(bindFailure.getSuppressed())
                    .as("the release failure is kept, and the binding failure stays the one thrown")
                    .containsExactly(releaseFailure);
        }
    }

    @Test
    @DisplayName("a successful binding leaves the engine holding its allocations until it closes")
    void successfulBindingKeepsTheEngine() {
        CommunityKernelCryptoProvider provider = new CommunityKernelCryptoProvider();
        TlsTestAuthority authority = TlsTestAuthority.root(material, "assembly-ok-ca");

        try (MemoryAllocator allocator = paranoidAllocator();
             CommunityTlsClientTrust trust = provider.openClientTrust(authority.certificate())) {
            CommunityTlsEngine engine = ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, allocator)
                    .call(() -> provider.createClientTlsEngine(
                            CryptoProviderConfig.tcpClient(), trust, TlsPeerIdentity.of("localhost")));
            MemoryStats open = allocator.stats();
            assertThat(open.releaseCount()).isLessThan(open.allocationCount());

            engine.close();

            MemoryStats closed = allocator.stats();
            assertThat(closed.releaseCount()).isEqualTo(closed.allocationCount());
        }
    }

    private static MemoryAllocator paranoidAllocator() {
        return new CommunityMemoryProvider().createAllocator(
                MemoryProviderConfig.defaults().withLeakDetection(LeakDetectionMode.PARANOID));
    }

    /**
     * Delegates every call. Once armed, the session slab's {@code close()} fails after the real slab
     * has been released, so the delegate's accounting still balances.
     */
    private static final class ReleaseFailingAllocator implements MemoryAllocator {

        private final MemoryAllocator delegate;
        private final RuntimeException releaseFailure;
        private volatile boolean failSessionReleases;

        ReleaseFailingAllocator(MemoryAllocator delegate, RuntimeException releaseFailure) {
            this.delegate = delegate;
            this.releaseFailure = releaseFailure;
        }

        void failSessionReleases() {
            failSessionReleases = true;
        }

        @Override
        public LoanedBuffer allocate(AllocationHint hint) {
            LoanedBuffer buffer = delegate.allocate(hint);
            return hint == AllocationHint.SESSION ? failingOnRelease(buffer) : buffer;
        }

        @Override
        public LoanedBuffer allocateNetwork(int estimatedBytes) {
            return delegate.allocateNetwork(estimatedBytes);
        }

        @Override
        public LoanedBuffer allocateCarrierSlab(int carrierIndex) {
            return delegate.allocateCarrierSlab(carrierIndex);
        }

        @Override
        public LoanedBuffer allocateInfrastructure(long sizeBytes) {
            return delegate.allocateInfrastructure(sizeBytes);
        }

        @Override
        public MemoryStats stats() {
            return delegate.stats();
        }

        @Override
        public void close() {
            delegate.close();
        }

        private LoanedBuffer failingOnRelease(LoanedBuffer slab) {
            InvocationHandler handler = (proxy, method, args) -> {
                try {
                    Object result = method.invoke(slab, args);
                    if (failSessionReleases && "close".equals(method.getName())) {
                        throw releaseFailure;
                    }
                    return result;
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            };
            return (LoanedBuffer) Proxy.newProxyInstance(
                    LoanedBuffer.class.getClassLoader(), new Class<?>[] {LoanedBuffer.class}, handler);
        }
    }
}
