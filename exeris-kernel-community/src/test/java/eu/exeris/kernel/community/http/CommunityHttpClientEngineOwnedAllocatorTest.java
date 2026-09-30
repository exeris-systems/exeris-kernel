/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.community.http;

import eu.exeris.kernel.community.memory.CommunityMemoryProvider;
import eu.exeris.kernel.community.transport.CommunityOutboundTls;
import eu.exeris.kernel.spi.context.KernelProviders;
import eu.exeris.kernel.spi.exceptions.KernelErrorCodes;
import eu.exeris.kernel.spi.exceptions.transport.TransportException;
import eu.exeris.kernel.spi.http.HttpConfig;
import eu.exeris.kernel.spi.http.HttpMode;
import eu.exeris.kernel.spi.http.HttpVersion;
import eu.exeris.kernel.spi.memory.AllocationHint;
import eu.exeris.kernel.spi.memory.LoanedBuffer;
import eu.exeris.kernel.spi.memory.MemoryAllocator;
import eu.exeris.kernel.spi.memory.MemoryProviderConfig;
import eu.exeris.kernel.spi.memory.MemoryStats;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A Community HTTP client engine closes the allocator it created for itself when its construction
 * fails, and never closes one that was bound.
 *
 * <p>The failing construction is a {@link CommunityOutboundTls#VERIFIED} requirement with no crypto
 * provider bound, which the transport refuses ({@code EX-NET-4004}) after the engine has created its
 * allocator. No engine exists on that path to close the allocator later.
 */
@DisplayName("CommunityHttpClientEngine — the allocator it creates is closed when construction fails")
class CommunityHttpClientEngineOwnedAllocatorTest {

    private static HttpConfig clientConfig() {
        return new HttpConfig(HttpMode.CLIENT, "127.0.0.1", -1,
                HttpConfig.DEFAULT_MAX_CONNECTIONS, HttpConfig.DEFAULT_IDLE_TIMEOUT_MS,
                HttpConfig.DEFAULT_MAX_HEADER_COUNT, HttpConfig.DEFAULT_MAX_HEADER_SIZE,
                HttpConfig.DEFAULT_MAX_REQUEST_BODY_BYTES, false, HttpVersion.HTTP_1_1);
    }

    @Test
    @DisplayName("a refused transport closes the allocator the engine created, once")
    void refusedTransportClosesTheOwnAllocator() {
        CountingAllocator own = new CountingAllocator(null);

        assertThatThrownBy(() -> new CommunityHttpClientEngine(
                clientConfig(), CommunityOutboundTls.VERIFIED, own.supplier()))
                .isInstanceOf(TransportException.class)
                .satisfies(e -> assertThat(((TransportException) e).errorCode())
                        .isEqualTo(KernelErrorCodes.EX_NET_4004));

        assertThat(own.created.get()).as("the engine created its own allocator").isEqualTo(1);
        assertThat(own.closes.get()).as("and closed it when the transport was refused").isEqualTo(1);
    }

    @Test
    @DisplayName("an allocator whose close fails is reported as suppressed on the refusal")
    void failingCloseIsSuppressedOnTheRefusal() {
        IllegalStateException closeFailure = new IllegalStateException("close failed");
        CountingAllocator own = new CountingAllocator(closeFailure);

        assertThatThrownBy(() -> new CommunityHttpClientEngine(
                clientConfig(), CommunityOutboundTls.VERIFIED, own.supplier()))
                .isInstanceOf(TransportException.class)
                .satisfies(e -> assertThat(e.getSuppressed()).containsExactly(closeFailure));

        assertThat(own.closes.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a bound allocator is not closed when the transport is refused, and none is created")
    void boundAllocatorIsNotClosedOnRefusal() {
        CountingAllocator bound = new CountingAllocator(null);
        CountingAllocator own = new CountingAllocator(null);

        ScopedValue.where(KernelProviders.MEMORY_ALLOCATOR, bound.supplier().get()).run(() ->
                assertThatThrownBy(() -> new CommunityHttpClientEngine(
                        clientConfig(), CommunityOutboundTls.VERIFIED, own.supplier()))
                        .isInstanceOf(TransportException.class));

        assertThat(bound.closes.get()).as("the bound allocator belongs to its binder").isZero();
        assertThat(own.created.get()).as("no allocator of its own while one is bound").isZero();
        bound.delegate().close();
    }

    @Test
    @DisplayName("an engine that is built keeps its own allocator open until the engine closes")
    void builtEngineClosesItsOwnAllocatorOnClose() {
        CountingAllocator own = new CountingAllocator(null);

        CommunityHttpClientEngine engine = new CommunityHttpClientEngine(
                clientConfig(), CommunityOutboundTls.PLAINTEXT, own.supplier());
        assertThat(own.closes.get()).as("open while the engine lives").isZero();

        engine.close();
        assertThat(own.closes.get()).as("closed with the engine").isEqualTo(1);
    }

    /** A real allocator that counts its closes, and can fail them. */
    private static final class CountingAllocator implements MemoryAllocator {

        private final AtomicInteger created = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();
        private final RuntimeException closeFailure;
        private MemoryAllocator delegate;

        private CountingAllocator(RuntimeException closeFailure) {
            this.closeFailure = closeFailure;
        }

        private Supplier<MemoryAllocator> supplier() {
            return () -> {
                created.incrementAndGet();
                delegate = new CommunityMemoryProvider().createAllocator(MemoryProviderConfig.defaults());
                return this;
            };
        }

        private MemoryAllocator delegate() {
            return delegate;
        }

        @Override
        public LoanedBuffer allocate(AllocationHint hint) {
            return delegate.allocate(hint);
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
            closes.incrementAndGet();
            delegate.close();
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }
}
